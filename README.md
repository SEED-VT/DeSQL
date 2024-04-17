
# DeSQL: Interactive Debugging of SQL in Data-Intensive Scalable Computing
*Sabaat Haroon, Chris Brown, and Muhammad Ali Gulzar. DeSQL: Interactive Debugging of SQL in
Data-Intensive Scalable Computing. The ACM International Conference on the Foundations of Software Engineering (FSE). Article 35 (July 2024), 21 pages. https://doi.org/10.1145/3643761.*

DeSQL is an interactive step-through debugging technique for DISC-backed SQL queries. This approach allows users to inspect constituent parts of a query and their corresponding intermediate data interactively, similar to watchpoints in gdb-like debuggers.


# 1. Setting Up the Environment 
### Step 1.1: Pre-requisites (Optional)

Ensure that `docker` is installed and running on your system. If it's not installed, please download Docker Desktop from [https://www.docker.com/products/docker-desktop/](https://www.docker.com/products/docker-desktop/).

These instructions have been verified with:
- **MacOS:** ProductVersion: 11.2.3, BuildVersion: 20D91
- **Docker:** 20.10.22, build 3a2c30b

Additionally, you will need:
- Python3
- Ports 4040 and 8080 should be available for DeSQL UI and Vanilla Spark UI, respectively.

### Step 1.2: Creating the Docker Image

### Clone the Repository

```sh
git clone https://github.com/SEED-VT/DeSQL.git
```

### Navigate to the cloned Repository
```sh
cd DeSQL
```
Ensure you can see all the files in this folder

```sh
ls
```

<img width="1333" alt="image" src="https://github.com/SEED-VT/DeSQL/assets/81311915/959ebc45-3a0c-4282-97d5-32b035749024">


### Clean Up

Remove any system-generated files to prevent interference with Docker builds:

```sh
find . -name '.DS_Store' -type f -delete
```

### Build the Docker Image

Navigate to the `spark-sql-debug` directory and build the Docker image:

```sh
cd spark-sql-debug
docker build -t my-custom-spark:latest -f dist/kubernetes/dockerfiles/spark/Dockerfile .
```

> **NOTE:** If you encounter permission issues, use `sudo` with Docker commands.

### Start Containers

Use Docker Compose to start the containers:

```sh
docker compose up -d
```

![image (1)](https://github.com/SEED-VT/DeSQL/assets/81311915/48f59ab9-8918-4696-be8f-7f312b548acc)


> **TROUBLESHOOTING:** If Docker Compose fails, restart Docker:

```sh
sudo systemctl restart docker
```

### Quick Links
- [DeSQL Artifact Demonstration](#2.-deSQL-artifact-demonstration)
- [DeSQL Evaluations Results](#3.-deSQL-evaluations )

# 2. DeSQL Artifact Demonstration

### Step 2.1: Submit a Spark SQL Query

Submit a Spark SQL job to the DeSQL container. Replace `query5.sql` with other queries as necessary, e.g., `query6.sql` or `query9.sql`. This artifact is pre-loaded with query1 to query10.

```sh
docker exec -it spark-local-container /opt/spark/bin/spark-submit \
--class DeSqlPackage.SQLTest.SQLTest \
--master "local[*]" \
--conf "spark.some.config.option=some-value" \
/opt/spark/app/desqlpackage_2.12-0.1.0-SNAPSHOT.jar /opt/spark/queries/query5.sql
```

> **Expected Observation:** DeSQL will start, and you can observe the logs in the console.

### Step 2.2: Visit the DeSQL UI

Access the DeSQL UI at [http://localhost:4040/debugger/](http://localhost:4040/debugger/). 

The UI displays sub-queries of the original query along with their data as processed within Spark computations. Additionally, it presents the query execution plan, with clickable green nodes for nodes with available sub-queries, to view the node's respective subquery and data.

### Step 2.3: DeSQL UI Walkthrough 







### Step 2.3: Terminating DeSQL 
To exit the running application, go to the terminal where Step 2.1 was performed and simply press Enter.



# 3. DeSQL Evaluations 

### Warning: Results Reproducibility Disclaimer

*Please be aware that the results reproduced here are **not** indicative of the actual overhead comparisons between DeSQL and Vanilla Spark, as reported in the research paper. The reported findings in the paper were derived from a 12-node cluster, comprising 1 name node and 11 data nodes collectively offering 104 cores, 53TB of storage, and 576GB of memory.*

*In contrast, the reproducibility exercises in this documentation are in a local mode for the ease of reviewing, using a small dataset. The goal is to demonstrate DeSQL's functionality and not to benchmark performance. The fluctuations observed in the reproducibility graphs reflect the initialization and execution times inherent to Spark jobs rather than the processing time related to data size. Since the demonstration uses a sample dataset with significantly fewer rows, the majority of the time measured may correspond to Spark's startup and operational procedures rather than the processing time  with and without DeSQL. However, to improve consistency in our local mode results, each query is executed twice, and the average of the two runs is presented. For a more accurate comparison that reflects the true overhead of DeSQL versus Vanilla Spark, one would need to replicate the computational environment and dataset size described in the original research paper.*


### Step 3.1: Collecting Experiment Results for DeSQL

Execute the script inside the DeSQL container to gather results:

```sh
docker exec -it spark-local-container /bin/bash -c "/opt/spark/desql_results.sh"
```

Note: Executing this script to compute results for all 10 queries typically takes approximately 5 minutes. Please be patient and wait for the process to complete.


Copy the results from the Docker container to your local machine:

```sh
docker cp spark-local-container:/opt/spark/examples/graphsFolder/data.txt ./data1.txt
```

Shut down the Docker containers:

```sh
docker compose down
```

### Step 3.2: Collecting Experiment Results for Vanilla Apache Spark

Change the directory to Vanilla Spark and build the Docker image:

```sh
cd ..
cd spark-3.0.0-bin-hadoop2.7
docker build -t my-vanilla-spark:latest -f kubernetes/dockerfiles/spark/Dockerfile .
```

Start the Vanilla Spark containers:

```sh
docker compose up -d
```

Execute the script to gather results from Vanilla Spark:

```sh
docker exec -it vanilla-spark-local-container /bin/bash -c "/opt/spark/spark_results.sh"
```

Note: Executing this script to compute results for all 10 queries typically takes approximately 5 minutes. Please be patient and wait for the process to complete.

Copy the Vanilla Spark results to your local machine:

```sh
docker cp vanilla-spark-local-container:/opt/spark/examples/graphsFolder/data.txt ./data2.txt
```

Shut down the Docker containers:

```sh
docker compose down
```

Navigate to the parent directory:

```sh
cd ..
```

### Step 3.3: Visualize the Experiment Results

Install necessary Python packages for analysis:

```sh
python3 -m pip install matplotlib pandas
```

Execute the analysis script:

```sh
python3 script.py ./spark-sql-debug/data1.txt ./spark-3.0.0-bin-hadoop2.7/data2.txt
```

### Step 3.4: Removing Docker Containers
Now you can remove docker containers from your local system:

```sh
docker rmi my-custom-spark
docker rmi my-local-spark
```
