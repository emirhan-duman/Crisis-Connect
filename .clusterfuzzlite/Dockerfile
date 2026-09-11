FROM gcr.io/oss-fuzz-base/base-builder@sha256:8b4a73d83374b298a0a771eeec9a1d44ceff3416a678b7d7946303389d022aca

COPY . $SRC/crisis-connect
COPY .clusterfuzzlite/build.sh $SRC/build.sh
WORKDIR $SRC/crisis-connect
