#!/bin/bash

# Remove the graphsFolder directory
rm -rf /opt/spark/examples/graphsFolder

# Outer loop for each query file
for i in {1..10}
do
  # Inner loop to repeat each query 2 times
  for j in {1..2}
  do
    /opt/spark/bin/spark-submit \
     --class DeSqlPackage.SQLTest.SQLTest \
     --master "local[*]" \
     --conf "spark.some.config.option=some-value" \
     /opt/spark/app/desqlpackage_withoutdebugger.jar /opt/spark/queries/query${i}.sql
    echo "Run $j of Query $i completed"
  done
done
