/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.s3.storage;

import com.amazonaws.AmazonClientException;
import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.SSEAlgorithm;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.amazonaws.services.s3.model.SSECustomerKey;
import com.amazonaws.services.s3.model.UploadPartRequest;
import io.confluent.connect.s3.S3SinkConnectorConfig;
import io.confluent.connect.storage.common.util.StringUtils;
import org.apache.kafka.connect.errors.DataException;
import org.apache.parquet.io.PositionOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * Output stream enabling multi-part uploads of Kafka records.
 *
 * <p>The implementation has borrowed the general structure of Hadoop's implementation.
 */
public class S3OutputStream extends PositionOutputStream {
    private static final Logger log = LoggerFactory.getLogger(S3OutputStream.class);
    private final AmazonS3 s3;
    private final S3SinkConnectorConfig connectorConfig;
    private final String bucket;
    private final String key;
    private final String ssea;
    private final SSECustomerKey sseCustomerKey;
    private final String sseKmsKeyId;
    private final ProgressListener progressListener;
    private final int partSize;
    private final CannedAccessControlList cannedAcl;
    private boolean closed;
    private ByteBuffer buffer;
    private MultipartUpload multiPartUpload;
    private final CompressionType compressionType;
    private final int compressionLevel;
    private volatile OutputStream compressionFilter;
    private Long position;

    public S3OutputStream(String key, S3SinkConnectorConfig conf, AmazonS3 s3) {
        this.s3 = s3;
        this.connectorConfig = conf;
        this.bucket = conf.getBucketName();
        this.key = key;
        this.ssea = conf.getSsea();
        final String sseCustomerKeyConfig = conf.getSseCustomerKey();
        this.sseCustomerKey = (SSEAlgorithm.AES256.toString().equalsIgnoreCase(ssea)
                && StringUtils.isNotBlank(sseCustomerKeyConfig))
                ? new SSECustomerKey(sseCustomerKeyConfig) : null;
        this.sseKmsKeyId = conf.getSseKmsKeyId();
        this.partSize = conf.getPartSize();
        this.cannedAcl = conf.getCannedAcl();
        this.closed = false;
        this.buffer = ByteBuffer.allocate(this.partSize);
        this.progressListener = new ConnectProgressListener();
        this.multiPartUpload = null;
        this.compressionType = conf.getCompressionType();
        this.compressionLevel = conf.getCompressionLevel();
        this.position = 0L;
        log.info("Create S3OutputStream for bucket '{}' key '{}'", bucket, key);
    }

    @Override
    public void write(int b) throws IOException {
        buffer.put((byte) b);
        if (!buffer.hasRemaining()) {
            uploadPart();
        }
        position++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (outOfRange(off, b.length) || len < 0 || outOfRange(off + len, b.length)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }

        if (buffer.remaining() <= len) {
            int firstPart = buffer.remaining();
            buffer.put(b, off, firstPart);
            position += firstPart;
            uploadPart();
            write(b, off + firstPart, len - firstPart);
        } else {
            buffer.put(b, off, len);
            position += len;
        }
    }

    private static boolean outOfRange(int off, int len) {
        return off < 0 || off > len;
    }

    private void uploadPart() throws IOException {
        uploadPart(partSize);
        buffer.clear();
    }

    private void uploadPart(final int size) throws IOException {
        if (multiPartUpload == null) {
            log.debug("New multi-part upload for bucket '{}' key '{}'", bucket, key);
            multiPartUpload = newMultipartUpload();
        }
        try {
            multiPartUpload.uploadPart(new ByteArrayInputStream(buffer.array()), size);
        } catch (Exception e) {
            if (multiPartUpload != null) {
                multiPartUpload.abort();
                log.debug("Multipart upload aborted for bucket '{}' key '{}'.", bucket, key);
            }
            throw new IOException("Part upload failed: ", e);
        }
    }

    public void commit() throws IOException {
        if (closed) {
            log.warn(
                    "Tried to commit data for bucket '{}' key '{}' on a closed stream. Ignoring.",
                    bucket,
                    key
            );
            return;
        }

        try {
            compressionType.finalize(compressionFilter);
            if (buffer.hasRemaining()) {
                uploadPart(buffer.position());
            }
            multiPartUpload.complete();
            log.debug("Upload complete for bucket '{}' key '{}'", bucket, key);
        } catch (Exception e) {
            log.error("Multipart upload failed to complete for bucket '{}' key '{}'", bucket, key);
            throw new DataException("Multipart upload failed to complete.", e);
        } finally {
            buffer.clear();
            multiPartUpload = null;
            internalClose();
        }
    }

    @Override
    public void close() throws IOException {
        internalClose();
    }

    private void internalClose() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (multiPartUpload != null) {
            multiPartUpload.abort();
            log.debug("Multipart upload aborted for bucket '{}' key '{}'.", bucket, key);
        }
        super.close();
    }

    private ObjectMetadata newObjectMetadata() {
        ObjectMetadata meta = new ObjectMetadata();
        if (StringUtils.isNotBlank(ssea)) {
            meta.setSSEAlgorithm(ssea);
        }
        return meta;
    }

    private MultipartUpload newMultipartUpload() throws IOException {
        InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(
                bucket,
                key,
                newObjectMetadata()
        ).withCannedACL(cannedAcl);

        if (SSEAlgorithm.KMS.toString().equalsIgnoreCase(ssea)
                && StringUtils.isNotBlank(sseKmsKeyId)) {
            log.debug("Using KMS Key ID: {}", sseKmsKeyId);
            initRequest.setSSEAwsKeyManagementParams(new SSEAwsKeyManagementParams(sseKmsKeyId));
        } else if (sseCustomerKey != null) {
            log.debug("Using KMS Customer Key");
            initRequest.setSSECustomerKey(sseCustomerKey);
        }

        return handleAmazonExceptions(
                () -> new MultipartUpload(s3.initiateMultipartUpload(initRequest).getUploadId())
        );
    }

    /**
     * Return the given Supplier value, converting any thrown AmazonClientException object
     * to an IOException object (containing the AmazonClientException object) and
     * throw that instead.
     *
     * @param supplier The supplier to evaluate
     * @param <T>      The object type returned by the Supplier
     * @return The value returned by the Supplier
     * @throws IOException Any IOException or AmazonClientException thrown while
     *                     retreiving the Supplier's value
     */
    private static <T> T handleAmazonExceptions(Supplier<T> supplier) throws IOException {
        try {
            return supplier.get();
        } catch (AmazonClientException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private class MultipartUpload {
        private final String uploadId;
        private final List<PartETag> partETags;

        public MultipartUpload(String uploadId) {
            this.uploadId = uploadId;
            this.partETags = new ArrayList<>();
            log.debug(
                    "Initiated multi-part upload for bucket '{}' key '{}' with id '{}'",
                    bucket,
                    key,
                    uploadId
            );
        }

        public HashMap convertByteArrayInputStreamToFile(ByteArrayInputStream inputStream, String filePath) {
            HashMap returnMap = new HashMap<>();
            Integer fileLineCount = 0;
            File file = new File(filePath);
            try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fileLineCount = fileLineCount + 1;
                    fileOutputStream.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                e.printStackTrace();
                // Handle the exception as per your application's requirement
            } finally {
                // Close the ByteArrayInputStream (optional, since it doesn't have associated resources)
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            returnMap.put("fileLineCount", fileLineCount);
            returnMap.put("sourceFile", file);
            log.info("Success in file creation");
            return returnMap;
        }


        public void uploadPart(ByteArrayInputStream inputStream, int partSize) {
            int currentPartNumber = partETags.size() + 1;
            String subUploadId = uploadId.substring(uploadId.length() - 10);
            String sourceFilePath = "/tmp/tempfile/tempSourceFile" + subUploadId + String.valueOf(new Date().getTime()) + ".json";
            String md5FilePath = "/tmp/tempfile/tempMD5File" + subUploadId + String.valueOf(new Date().getTime()) + ".json";
            HashMap s3FileMap = convertByteArrayInputStreamToFile(inputStream, sourceFilePath);
            File md5File = new File(md5FilePath);
            File s3File = (File) s3FileMap.get("sourceFile");

            Integer sourceFileLine = (Integer) s3FileMap.get("fileLineCount");
            log.info("Inside uploadPart , sourceFileLine : " + sourceFileLine);
            try (BufferedReader reader = new BufferedReader(new FileReader(s3File));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(md5File))) {

                String line;
                int lineCount = 0;

                while ((line = reader.readLine()) != null && lineCount < sourceFileLine) {
                    writer.write(line);
                    writer.newLine();
                    lineCount++;
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
            log.info("Inside uploadPart md5File file created : " + md5File.exists());

            UploadPartRequest request = new UploadPartRequest()
                    .withBucketName(bucket)
                    .withKey(key)
                    .withUploadId(uploadId)
                    .withSSECustomerKey(sseCustomerKey)
                    .withPartNumber(currentPartNumber)
                    .withFile(s3File)
                    .withPartSize(partSize)
                    .withGeneralProgressListener(progressListener);

            if (connectorConfig.uploadIntegrityCheck()) {
                try {
                    FileInputStream fis = new FileInputStream(md5File);
                    String digest = Base64.getEncoder().encodeToString(DigestUtils.md5(fis));
                    log.info("Inside uploadPart , digest : " + digest);

                    request.setMd5Digest(digest);
                } catch (Exception e) {
                    log.error("Error calculating md5 digest for part {} for id '{}'; skipping check", e);
                }
            }
            log.debug("Uploading part {} for id '{}'", currentPartNumber, uploadId);
            partETags.add(s3.uploadPart(request).getPartETag());
            s3File.delete();
            md5File.delete();
            log.info("Done upload");
        }

        public void complete() throws IOException {
            log.debug("Completing multi-part upload for key '{}', id '{}'", key, uploadId);
            CompleteMultipartUploadRequest completeRequest =
                    new CompleteMultipartUploadRequest(bucket, key, uploadId, partETags);

            handleAmazonExceptions(
                    () -> s3.completeMultipartUpload(completeRequest)
            );
        }

        public void abort() {
            log.warn("Aborting multi-part upload with id '{}'", uploadId);
            try {
                s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId));
            } catch (Exception e) {
                // ignoring failure on abort.
                log.warn("Unable to abort multipart upload, you may need to purge uploaded parts: ", e);
            }
        }
    }

    public OutputStream wrapForCompression() {
        if (compressionFilter == null) {
            // Initialize compressionFilter the first time this method is called.
            compressionFilter = compressionType.wrapForOutput(this, compressionLevel);
        }
        return compressionFilter;
    }

    // Dummy listener for now, just logs the event progress.
    private static class ConnectProgressListener implements ProgressListener {
        public void progressChanged(ProgressEvent progressEvent) {
            log.debug("Progress event: " + progressEvent);
        }
    }

    public long getPos() {
        return position;
    }
}
