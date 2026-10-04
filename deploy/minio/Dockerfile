FROM alpine:3.20
# Official pinned release binaries are downloaded and SHA-256 verified by prepare-minio.ps1.
# Building locally avoids relying on the retired public MinIO image repositories.
COPY deploy/minio/bin/minio /usr/local/bin/minio
COPY deploy/minio/bin/mc /usr/local/bin/mc
RUN chmod 755 /usr/local/bin/minio /usr/local/bin/mc
EXPOSE 9000 9001
CMD ["minio", "server", "/data", "--console-address", ":9001"]
