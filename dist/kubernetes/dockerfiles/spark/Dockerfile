#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
ARG java_image_tag=8-jre-slim

FROM openjdk:${java_image_tag}

ARG spark_uid=185

# Before building the docker image, first build and make a Spark distribution following
# the instructions in http://spark.apache.org/docs/latest/building-spark.html.
# If this docker file is being used in the context of building your images from a Spark
# distribution, the docker build command should be invoked from the top level directory
# of the Spark distribution. E.g.:
# docker build -t spark:latest -f kubernetes/dockerfiles/spark/Dockerfile .

USER root

RUN set -ex && \
    sed -i 's/http:/https:/g' /etc/apt/sources.list && \
    apt-get update && \
    ln -s /lib /lib64 && \
    apt install -y bash tini libc6 libpam-modules krb5-user libnss3 && \
    mkdir -p /opt/spark && \
    mkdir -p /opt/spark/examples && \
    mkdir -p /opt/spark/work-dir && \
    touch /opt/spark/RELEASE && \
    rm /bin/sh && \
    ln -sv /bin/bash /bin/sh && \
    echo "auth required pam_wheel.so use_uid" >> /etc/pam.d/su && \
    chgrp root /etc/passwd && chmod ug+rw /etc/passwd && \
    rm -rf /var/cache/apt/*



COPY assembly/target/scala-2.12/jars /opt/spark/jars
COPY bin /opt/spark/bin
COPY sbin /opt/spark/sbin
COPY dist/kubernetes/dockerfiles/spark/entrypoint.sh /opt/
COPY examples /opt/spark/examples
RUN chmod -R 777 /opt/spark/examples

COPY dist/kubernetes/tests /opt/spark/tests
COPY data /opt/spark/data

COPY desqlpackage_2.12-0.1.0-SNAPSHOT.jar /opt/spark/app/desqlpackage_2.12-0.1.0-SNAPSHOT.jar


COPY Queries /opt/spark/queries


ENV SPARK_HOME /opt/spark

WORKDIR /opt/spark/work-dir
RUN chmod g+w /opt/spark/work-dir

# Create and set permissions for the Spark work directory
RUN mkdir -p /opt/spark/work && \
    chown -R ${spark_uid}:${spark_uid} /opt/spark/work && \
    chmod -R 775 /opt/spark/work


# Create the app directory and ensure it has the right permissions
RUN mkdir -p /opt/spark/app && \
    chown -R ${spark_uid}:${spark_uid} /opt/spark/app && \
    chmod -R 775 /opt/spark/app


ENTRYPOINT [ "/opt/entrypoint.sh" ]

# Specify the User that the actual main process will run as
USER ${spark_uid}
