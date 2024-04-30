
# DeSQL: Interactive Debugging of SQL in Data-Intensive Scalable Computing
*Sabaat Haroon, Chris Brown, and Muhammad Ali Gulzar. DeSQL: Interactive Debugging of SQL in
Data-Intensive Scalable Computing. The ACM International Conference on the Foundations of Software Engineering (FSE). Article 35 (July 2024), 21 pages. https://doi.org/10.1145/3643761.*

DeSQL is an interactive step-through debugging technique for DISC-backed SQL queries. This approach allows users to inspect constituent parts of a query and their corresponding intermediate data interactively, similar to watchpoints in gdb-like debuggers.


# 1. Setting Up the Environment 
### Step 1.1: Pre-requisites (Optional)

Ensure that `docker` is installed and running on your system. If it's not installed, please download Docker Desktop from [https://www.docker.com/products/docker-desktop/](https://www.docker.com/products/docker-desktop/).

These instructions have been verified with:
- **MacOS:** ProductVersion: 11.2.3, BuildVersion: 20D91
- **Ubuntu:** 22.04 LTS
- **Docker:** 20.10.22, build 3a2c30b

Additionally, you will need:
- Python3
- Ports 4040 and 8080 should be available for DeSQL UI and Vanilla Spark UI, respectively.

Note: This ReadMe is not directly compatible with Windows; users will need to modify the commands to fit the Windows format.

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
Here is what a successful initialization of a Docker container should look like:

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

## Successful Launch of the DeSQL UI

Upon a successful launch, the DeSQL UI, accessible at [http://localhost:4040/debugger/](http://localhost:4040/debugger/), presents a interface structured as:

- **Left Side**: Displays the original SQL query submitted to Spark.
- **Right Side**: Shows the plan tree generated by Spark to execute the query. This plan tree contains two types of nodes:
  - **Green nodes**: These are clickable and directly correspond to subqueries. Users can interact with these nodes to go directly to their respective subqueries by clicking on those nodes.
  - **Red nodes**: These nodes indicate queries that have been further decomposed into additional nodes. While these red nodes themselves do not hold data, their child nodes will contain the resultant data.
  
The UI landing page with the original query on the left side and plan tree on the right side:
![View of the plan tree](https://github.com/SEED-VT/DeSQL/assets/81311915/277ae90e-2f23-4cd2-8974-36a2608226e8)

As you scroll, you'll encounter the complete list of possible subqueries of the original query along with their respective data:
![Subqueries and data](https://github.com/SEED-VT/DeSQL/assets/81311915/0d0ec1bd-30f9-4e88-942d-2412f06b1adc)

### Interacting with Data
Each subquery includes a "View Rows" button, enabling users to examine the data associated with each subquery:

<img width="759" alt="image (5)" src="https://github.com/SEED-VT/DeSQL/assets/81311915/75bf2c08-4a0b-4257-a94e-b0d39d36fb3c">

For better readability, the first five rows of each query are displayed initially, with an option to show more. Each click on Show More will load another 5 rows of the query:

<img width="768" alt="image (6)" src="https://github.com/SEED-VT/DeSQL/assets/81311915/5e5033f2-8853-4ef6-a30b-37370ff8c391">

### Plan to Query Mapping
Clicking on the green node, such as one highlighted `cd_gender=F`, will directly navigate to its corresponding subquery, providing a focused view on that subquery:
- ![Highlighted Node Interaction](https://github.com/SEED-VT/DeSQL/assets/81311915/fe631cdd-47f5-4b4c-89ef-5c47520016b9)
- ![Resultant Subquery](https://github.com/SEED-VT/DeSQL/assets/81311915/186864ff-f118-4a29-b720-28b6daeb7288)


### Step 2.3: Terminating DeSQL 
To exit the running application, go to the terminal where Step 2.1 was performed and simply press Enter.


# 3. DeSQL Evaluations 

### Results Reproducibility Disclaimer

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

Here is what a successful termination of a Docker container should look like:
![Successful Docker Stop](https://github.com/SEED-VT/DeSQL/assets/81311915/05759dc4-fc08-4f9f-890d-f5d2987010a0)


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

Here is what a successful initialization of a Docker container should look like:

![image (10)](https://github.com/SEED-VT/DeSQL/assets/81311915/b162564f-a8c7-4d6a-b1ff-f0f83e95623f)


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

Here is what a successful termination of a Docker container should look like:
![image (11)](https://github.com/SEED-VT/DeSQL/assets/81311915/1e8bfc45-6aa1-4a90-ae5f-c1666a773b05)


Navigate to the parent directory:

```sh
cd ..
```

### Step 3.3: Visualize the Experiment Results

Install necessary Python packages for analysis:

```sh
python3 -m pip install matplotlib pandas
```

Here is the example of what a successful run of this command looks like:

![image (12)](https://github.com/SEED-VT/DeSQL/assets/81311915/e1ff8743-64c2-49f4-8186-18b5df85757d)



Execute the analysis script:

```sh
python3 script.py ./spark-sql-debug/data1.txt ./spark-3.0.0-bin-hadoop2.7/data2.txt
```

Running this command will generate a graph and should automatically appear on your screen. A successful run of this command will show you a graph similar to this:

![image (13)](https://github.com/SEED-VT/DeSQL/assets/81311915/e0d206d8-ec34-4e6e-b9d7-3f7d6681c689)



### Step 3.4: Removing Docker Containers
Now you can remove docker containers from your local system:

```sh
docker rmi my-custom-spark
docker rmi my-local-spark
```
