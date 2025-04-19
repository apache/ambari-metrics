<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Build Instructions for Apache Ambari Metrics

## Overview
Apache Ambari Metrics is a subproject of Apache Ambari, designed to provide a scalable, reliable metric collection and monitoring framework for large-scale distributed systems. This document outlines the steps to build the project from source.

---

## Prerequisites

Before you begin, ensure that the following dependencies are installed and configured on your system:

### Required Tools
1. **Java Development Kit (JDK)**
   - Version: JDK 8 or later
   - Ensure `JAVA_HOME` is set in your environment variables.
   - Example:
     ```bash
     export JAVA_HOME=/path/to/jdk
     export PATH=$JAVA_HOME/bin:$PATH
     ```

2. **Apache Maven**
   - Version: 3.6 or later
   - Download and install from [Maven's official website](https://maven.apache.org/).

3. **Git**
   - Version: Any modern version
   - Install Git from [Git's official website](https://git-scm.com/).

4. **Python3**
   -- You also need to install additional packages and Python development header files.
      Example:
      ```bash
      yum install -y krb5-devel python36-devel gcc
      pip3 install distro
      pip3 install kerberos
      ```

4. **GCC**
   -- Example:
      ```bash
      yum install -y gcc python36-devel
      ```
---

## Clone the Repository
Start by cloning the repository to your local machine:
```bash
git clone https://github.com/apache/ambari-metrics.git
cd ambari-metrics
```

---

## Build the Project
To build the Ambari Metrics project, follow these steps:

1. **Clean the Project**
   Clean up any previous builds:
   ```bash
   mvn clean
   ```

2. **Compile the Source Code**
   Compile the source code and ensure there are no errors:
   ```bash
   mvn compile
   ```

3. **Run Tests**
   Run the unit tests to ensure everything is functioning as expected:
   ```bash
   mvn test
   ```

4. **Package the Project**
   Package the project binaries into JARs:
   ```bash
   mvn package -DskipTests -Drat.skip=true
   ```

5. **Build RPM packages**
   ```bash
   mvn -T 2C clean package -Prpm -DskipTests
   ```

   Locate your .rpm
   ```bash

   ./ambari-metrics-assembly/target/rpm/ambari-metrics-monitor/RPMS/x86_64/ambari-metrics-monitor-3.1.0-1.x86_64.rpm
   ./ambari-metrics-assembly/target/rpm/ambari-metrics-grafana/RPMS/x86_64/ambari-metrics-grafana-3.1.0-1.x86_64.rpm
   ./ambari-metrics-assembly/target/rpm/ambari-metrics-hadoop-sink/RPMS/x86_64/ambari-metrics-hadoop-sink-3.1.0-1.x86_64.rpm
   ./ambari-metrics-assembly/target/rpm/ambari-metrics-collector/RPMS/x86_64/ambari-metrics-collector-3.1.0-1.x86_64.rpm

   ```

   Build ARM64 RPM packages
   ```bash
   mvn clean package -Prpm -Drpm.arch=aarch64 -DskipTests
   ```

   Locate your .rpm
   ```bash

   ./ambari-metrics-assembly/target/rpm/ambari-metrics-monitor/RPMS/aarch64/ambari-metrics-monitor-3.1.0-1.aarch64.rpm
   ./ambari-metrics-assembly/target/rpm/ambari-metrics-grafana/RPMS/aarch64/ambari-metrics-grafana-3.1.0-1.aarch64.rpm
   ./ambari-metrics-assembly/target/rpm/ambari-metrics-hadoop-sink/RPMS/aarch64/ambari-metrics-hadoop-sink-3.1.0-1.aarch64.rpm
   ./ambari-metrics-assembly/target/rpm/ambari-metrics-collector/RPMS/aarch64/ambari-metrics-collector-3.1.0-1.aarch64.rpm

   ```
