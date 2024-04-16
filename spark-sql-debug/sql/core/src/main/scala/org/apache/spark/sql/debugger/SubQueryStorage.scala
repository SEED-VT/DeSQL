package org.apache.spark.sql.debugger
import org.apache.hadoop.conf.Configuration
import org.apache.spark.sdb._
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.catalyst.plans.logical.SubqueryAlias
import org.apache.spark.sql.catalyst.plans.logical.{DataRegeneratorVisitor, SubQueryGeneratorVisitor}
import org.apache.spark.rdd.{PairRDDFunctions, RDD}
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.{SparkListenerApplicationEnd, SparkListenerApplicationStart, SparkListenerBlockManagerAdded, SparkListenerBlockManagerRemoved, SparkListenerBlockUpdated, SparkListenerEnvironmentUpdate, SparkListenerEvent, SparkListenerExecutorAdded, SparkListenerExecutorBlacklisted, SparkListenerExecutorBlacklistedForStage, SparkListenerExecutorMetricsUpdate, SparkListenerExecutorRemoved, SparkListenerExecutorUnblacklisted, SparkListenerInterface, SparkListenerJobEnd, SparkListenerJobStart, SparkListenerNodeBlacklisted, SparkListenerNodeBlacklistedForStage, SparkListenerNodeUnblacklisted, SparkListenerSpeculativeTaskSubmitted, SparkListenerStageCompleted, SparkListenerStageExecutorMetrics, SparkListenerStageSubmitted, SparkListenerTaskEnd, SparkListenerTaskGettingResult, SparkListenerTaskStart, SparkListenerUnpersistRDD}
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.storage.StorageLevel
import java.io.{BufferedWriter, File, FileWriter, PrintWriter}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import scala.collection.mutable.ArrayBuffer
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.Filter
import org.apache.spark.sql.catalyst.plans.logical.SubqueryAlias
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation


case class SubQueryData(subQuery:String,dataRdd:RDD[GenericRowWithSchema])

case class PlanNode(name: String, children: Seq[PlanNode], mappingIndex: Int)

// JSON Writes for PlanNode to enable conversion to JSON


  class DeSql extends Logging {


    def beautifyNodeName(name: String): String = {
      // Check if the name starts with "Project" and trim the rest
      val projectNameTrimmed = if (name.startsWith("Project")) "Project" else name

      // Compile patterns for removal
      val patterns = Seq(
        "\\bcast\\b" -> "", // Removes the word "cast"
        "#\\d+" -> "", // Removes patterns like "#xyz"
        "(?i)\\bas int\\b" -> "", // Removes "as int" (case insensitive)
        "(?i)\\bas string\\b" -> "", // Removes "as string" (case insensitive)
        "(?i)\\bas float\\b" -> "" // Removes "as float" (case insensitive)
      )

      // Apply all replacements
      patterns.foldLeft(projectNameTrimmed) { case (currentName, (pattern, replacement)) =>
        currentName.replaceAll(pattern, replacement)
      }.trim // Trim to remove any leading/trailing spaces left after replacements
    }


    def convertToPlanNode(plan: LogicalPlan): PlanNode = {
      def convert(plan: LogicalPlan): Seq[PlanNode] = {
        plan match {
          case aliasNode: SubqueryAlias if plan.getMappingIndex() == -1 =>
            // Fetch the LogicalRelation node from the children attribute
            val logicalRelationNodeOption = aliasNode.children.find(_.isInstanceOf[LogicalRelation])
            logicalRelationNodeOption match {
              case Some(logicalRelation: LogicalRelation) =>
                val name = beautifyNodeName(aliasNode.simpleString(maxFields = 1)) // Fetch the alias/table name
                // Directly create a PlanNode with the combined info
                Seq(PlanNode(name, Seq.empty, logicalRelation.getMappingIndex()))
              case None =>
                // Handle the case where no LogicalRelation child is found under the SubQueryAlias node
                Seq.empty // Or handle it appropriately depending on your application's needs
            }

          case filterNode: Filter =>
            // Directly process children, ensuring to include the condition
            val childrenNodes = filterNode.allChildren.toSeq.flatMap {
              case child: LogicalPlan => convert(child)
              case _ => Seq.empty
            }
            val conditionNodes = convertExpressionToPlanNode(filterNode.condition)
            childrenNodes ++ conditionNodes // Correctly combine sequences

          case _ if plan.getMappingIndex() != -1 =>
            val name = beautifyNodeName(plan.simpleString(maxFields = 1))
            val allChildNodes = plan.allChildren.toSeq.flatMap {
              case child: LogicalPlan => convert(child)
              case _ => Seq.empty
            }
            val extraNodes = plan match {
              case filterNode: Filter => convertExpressionToPlanNode(filterNode.condition) // Here, handle sequence directly
              case _ => Seq.empty
            }
            // Merge all children and any extra nodes
            Seq(PlanNode(name, allChildNodes ++ extraNodes, plan.getMappingIndex()))

          case _ =>
            plan.allChildren.toSeq.flatMap {
              case child: LogicalPlan => convert(child)
              case _ => Seq.empty
            }
        }
      }

      // Assuming there's a way to create a single PlanNode from a sequence of PlanNodes,
      // possibly by aggregating them or picking the first. Adjust according to your actual implementation.



    // Ensure there's a logical way to handle the potential for multiple root nodes
      val nodes = convert(plan)
      if (nodes.size > 1) {
        PlanNode("SyntheticRoot", nodes, 0) // Handling multiple root nodes
      } else {
        nodes.head
      }
  }

    // Assume `convertExpressionToPlanNode` remains as previously defined


    // Function to convert Expression to PlanNode, assuming similar structure and methods as LogicalPlan
    def convertExpressionToPlanNode(expr: Expression): Seq[PlanNode] = {
      if (expr.getMappingIndex() == -1) {
        // For expressions with mappingIndex of -1, do not include them in the tree,
        // but still traverse their children.
        expr.allChildren.toSeq
          .filter(_.isInstanceOf[Expression])
          .map(_.asInstanceOf[Expression])
          .flatMap(convertExpressionToPlanNode) // Continue traversal without adding the current node
      } else {
        // Convert this expression to a PlanNode and include it in the tree,
        // along with all its child expressions.
        val allChildExpressions: Seq[Expression] = expr.allChildren.toSeq
          .filter(_.isInstanceOf[Expression]) // Filter to retain only instances of Expression
          .map(_.asInstanceOf[Expression])
          .toSeq // Convert to Seq

        Seq(PlanNode(
          name = beautifyNodeName(expr.simpleString(maxFields = 1)),
          children = allChildExpressions.flatMap(convertExpressionToPlanNode), // Recursively convert child expressions
          mappingIndex = expr.getMappingIndex()
        ))
      }
    }


    // Function to convert a LogicalPlan to XML
    def convertPlanToXml(planNode: PlanNode): scala.xml.Elem = {
      <node name={planNode.name} mappingIndex={planNode.mappingIndex.toString}>
        {planNode.children.map(convertPlanToXml)}
      </node>
    }



    def debuggerForSql(teenagers:DataFrame,spark:SparkSession,t1:Long,outputPath:String,originalQuery:String) : Unit = {
    val subQueryList = ArrayBuffer[SubQueryStorage]()
    val subQueryGeneratorVisitorInstance = new SubQueryGeneratorVisitor
    val allSubQueries = {
      teenagers.queryExecution.analyzed.accept(subQueryGeneratorVisitorInstance, subQueryList)
    }

    spark.sparkContext.setBasePath(outputPath)
    spark.sparkContext.setOriginalQuery(originalQuery)
    spark.sparkContext.setAllSubQueries(allSubQueries.size)
    teenagers.rdd.saveAsTextFile(outputPath + "/results")

    var fileNameMain = "query"
    var fullFolderPathMain = outputPath + "/results"
    val fullPathMain = s"$fullFolderPathMain/$fileNameMain.txt"

    try {
      val writerMain = new PrintWriter(new File(fullPathMain))
      writerMain.write(originalQuery + "$")
      writerMain.flush()
      writerMain.close()
    }
    catch {
      case e: Exception => println(s"An error occurred: ${e.getMessage}")
    }

    var fileNumber = 1
    val regenerationAllSubQueries = ArrayBuffer[SubQueryStorage]()
    val existingData = spark.getDebugBuffer()
    var leftTableIndex =0
    var rightTableIndex =0
    existingData.persist(StorageLevel.MEMORY_AND_DISK)
    var debuggerResults = new ArrayBuffer[SubQueryData]
    allSubQueries.foreach(x => {
      if(x.regenFlag == true){
        if(x.leftTableFieldIndex!=0){
          leftTableIndex = x.leftTableFieldIndex
          rightTableIndex = x.rightTableFieldIndex
        }
        regenerationAllSubQueries.append(new SubQueryStorage(x.subQueryString,x.subQueryIndex))
      } else{
        var resultFilteredRDD = existingData.filter(z => z._3 == x.subQueryIndex)

        if (!resultFilteredRDD.isEmpty()) {

          var schemaCols = resultFilteredRDD.first()._5

          val queryParts = x.subQueryString.split(" from ", 2)

          // Replace the select list with schemaCols while keeping the FROM part unchanged
          if (queryParts.length == 2) {
            val before = queryParts(0) // The SELECT part
            val after = queryParts(1) // The FROM part and the rest of the query

            // Create a new query string with the new columns and the old FROM part
            x.subQueryString = s"SELECT $schemaCols from $after"
          }
        }
        var resultFilteredRDDRown = resultFilteredRDD.map(z => z._1)
        var queryData = resultFilteredRDDRown.collect()
        var queryDataString = queryData.map(a => a.toString())
        resultFilteredRDDRown.saveAsTextFile(outputPath + "/results" + fileNumber)
        var fileName = "query" + fileNumber
        var fullFolderPath = outputPath + "/results" + fileNumber
        val fullPath = s"$fullFolderPath/$fileName.txt"
        val indexFileName = s"$fullFolderPath/index$fileNumber.txt"

        try {
          val writer = new PrintWriter(new File(fullPath))
          writer.write(x.subQueryString + "$")
          fileNumber = fileNumber + 1
          println(s"Content has been written to $fullPath")
          writer.flush()
          writer.close()
        }
        catch {
          case e: Exception => println(s"An error occurred: ${e.getMessage}")
        }
        try {
          val indexWriter = new PrintWriter(new File(indexFileName))
          indexWriter.write(x.subQueryIndex.toString) // Assuming subQueryIndex is an Int or convert it to String
          indexWriter.flush()
          indexWriter.close()
        } catch {
          case e: Exception => println(s"Error writing subquery index to $indexFileName: ${e.getMessage}")
        }
      }
    })

    subQueryGeneratorVisitorInstance.enabled = true
    var regenerationData = existingData
    if (subQueryGeneratorVisitorInstance.enabled == true) {
    regenerationData = {
        teenagers.queryExecution.analyzed.acceptRegeneration(new DataRegeneratorVisitor, existingData,leftTableIndex,rightTableIndex)
      }
    }

      val logicalPlan = teenagers.logicalPlan
      val planXml = convertPlanToXml(convertToPlanNode(logicalPlan)).toString()
      spark.sparkContext.setPlanTree(planXml)
    regenerationAllSubQueries.foreach(x => {
      var resultFilteredRDD = regenerationData.filter(z => z._3 == x.subQueryIndex)

      if (!resultFilteredRDD.isEmpty()) {

        var schemaCols = resultFilteredRDD.first()._5
        val queryParts = x.subQueryString.split(" from ", 2)

        // Replace the select list with schemaCols while keeping the FROM part unchanged
        if (queryParts.length == 2) {
          val before = queryParts(0) // The SELECT part
          val after = queryParts(1) // The FROM part and the rest of the query

          // Create a new query string with the new columns and the old FROM part
          x.subQueryString = s"SELECT $schemaCols from $after"
        }
      }

      var resultFilteredRDDRown = resultFilteredRDD.map(z => z._1)
      var queryData = resultFilteredRDDRown.collect()
      var queryDataString = queryData.map(a => a.toString())
      resultFilteredRDDRown.saveAsTextFile(outputPath + "/results" + fileNumber)
      var fileName = "query" + fileNumber
      var fullFolderPath = outputPath + "/results" + fileNumber
      val fullPath = s"$fullFolderPath/$fileName.txt"
      val indexFileName = s"$fullFolderPath/index$fileNumber.txt"
      try {
        val writer = new PrintWriter(new File(fullPath))
        writer.write(x.subQueryString + "$")
        fileNumber = fileNumber + 1
        writer.flush()
        writer.close()
      }
      catch {
        case e: Exception => println(s"An error occurred: ${e.getMessage}")
      }
      try {
        val indexWriter = new PrintWriter(new File(indexFileName))
        indexWriter.write(x.subQueryIndex.toString) // Assuming subQueryIndex is an Int or convert it to String
        indexWriter.flush()
        indexWriter.close()
      } catch {
        case e: Exception => println(s"Error writing subquery index to $indexFileName: ${e.getMessage}")
      }
    })
  }
}