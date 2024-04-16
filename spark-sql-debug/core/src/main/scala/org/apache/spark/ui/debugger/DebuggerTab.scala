/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ui.debugger

import javax.servlet.http.HttpServletRequest

import scala.xml.Node
import scala.util.Try


import org.apache.spark.internal.config.UI._
import org.apache.spark.SparkContext
import org.apache.spark.ui.{SparkUI, SparkUITab, UIUtils, WebUIPage}
import scala.io.Source
import scala.xml.Unparsed
import java.io.File
import org.apache.spark.status.api.v1._




private[ui] class DebuggerTab(parent: SparkUI) extends SparkUITab(parent, "debugger") {

  init()

  private def init(): Unit = {
    val sc = parent.sc.getOrElse(throw new IllegalStateException("SparkContext is not available"))
    attachPage(new DebuggerPage(this, sc))
  }

}

// This is a placeholder for your custom case class or type that holds the query and rows data
case class SubQueryResult(subQueryString: String, rows: Seq[String])
//case class SparkInfo(activeJobs: Int, totalCores: Int)
case class PlanNode(name: String, children: Seq[PlanNode])



private[ui] class DebuggerPage(parent: SparkUITab, sc: SparkContext) extends WebUIPage("") {

  def renderPlanNodeTree(planNode: PlanNode): Seq[Node] = {
    val childNodes = planNode.children.flatMap(renderPlanNodeTree)
    if (childNodes.isEmpty) {
      Seq(<li>
        {planNode.name}
      </li>)
    } else {
      Seq(
        <li>
          {planNode.name}<ul>
          {childNodes}
        </ul>
        </li>
      )
    }
  }

  //  private def fetchSparkInfo(): SparkInfo = {
  //    // Use getOption and getOrElse to provide a default value
  //    val activeJobs = sc.statusTracker.getActiveJobIds().length
  //    // Providing default values if the configuration is not set
  //    val totalCores = sc.getConf.getOption("spark.executor.instances").getOrElse("1").toInt *
  //      sc.getConf.getOption("spark.executor.cores").getOrElse("1").toInt
  //    SparkInfo(activeJobs, totalCores)
  //  }

  def renderPlanNodeTreeXml(planXml: scala.xml.Node, availableMappings: Set[String]): Seq[Node] = {
    val childNodes = planXml.child.filter(_.label == "node").flatMap(node => renderPlanNodeTreeXml(node, availableMappings))
    var nodeName = (planXml \@ "name").toString
    val nodeIndex = (planXml \@ "mappingIndex").toString
    val nodeClass = if (availableMappings.contains(nodeIndex)) "node-mapped" else "node-unmapped"

    nodeName = nodeName match {
      case "Project" => "Select"
      case name if name.contains("SubqueryAlias") => name.replace("SubqueryAlias", "From")
      case _ => nodeName // Leave other node names unchanged
    }


    if (childNodes.isEmpty) {
      Seq(
        <li class={nodeClass}>
          <button type="button" class="node-button" onclick={"handleClick(this.getAttribute('data-mapping-index'))"} data-mapping-index={nodeIndex}>
            {nodeName}
          </button>
        </li>
      )
    } else {
      Seq(
        <li class={nodeClass}>
          <button type="button" class="node-button" onclick={"handleClick(this.getAttribute('data-mapping-index'))"} data-mapping-index={nodeIndex}>
            {nodeName}
          </button>
          <ul>
            {childNodes}
          </ul>
        </li>
      )
    }
  }


  private def readSubQueryResults(fileNumber: Int, basePath: String): SubQueryResult = {
    // Construct the directory and file paths

    val folderPath = s"$basePath/results$fileNumber"
    val queryFilePath = s"$folderPath/query$fileNumber.txt"
    val resultDir = new File(folderPath)


    // Read the query string from the file
    val queryRaw = Source.fromFile(queryFilePath).getLines.mkString("\n")
    val query = cleanQueryString(queryRaw) // Clean the query string


    // Read all the part-xxxxx files as the RDD rows
    val rows = resultDir.listFiles
      .filter(_.getName.startsWith("part-"))
      .flatMap(file => Source.fromFile(file).getLines)
      .toSeq

    SubQueryResult(query, rows)
  }

  private def cleanQueryString(query: String): String = {
    query
      .replaceAll("#\\d+", "") // Remove # followed by numbers
      .replaceAll("\\$$", "") // Remove trailing dollar signs
  }

  def readSubQueryIndex(fileNumber: Int, basePath: String): Int = {
    val indexPath = s"$basePath/results$fileNumber/index$fileNumber.txt"
    Try(scala.io.Source.fromFile(indexPath).getLines.mkString.toInt).getOrElse(-1) // Return -1 or any sentinel value on failure
  }

  def render(request: HttpServletRequest): Seq[Node] = {
    // Fetch the original query
    if (sc.getDebuggerFlag) {

      //      val sparkInfo = fetchSparkInfo()
      //      // Example Spark info display content
      //      val sparkInfoDisplay = <div style="position:absolute; right:10px; top:50px;">
      //        <p>Active Jobs:
      //          {sparkInfo.activeJobs}
      //        </p>
      //        <p>Total Cores:
      //          {sparkInfo.totalCores}
      //        </p>
      //      </div>

      val originalQuery = sc.getOriginalQuery
      val allSubqueriesCount = sc.getAllSubQueries
      val basePath = sc.getBasePath

      val availableMappings: Set[String] = (1 to allSubqueriesCount).map { fileNumber =>
        readSubQueryIndex(fileNumber, basePath).toString
      }.toSet

      // Generate the subqueries content dynamically
      val subqueriesContent = (1 to allSubqueriesCount).flatMap { fileNumber =>
        val subQuery = readSubQueryResults(fileNumber, basePath)
        val mappingIndex = readSubQueryIndex(fileNumber, basePath) // Read the mappingIndex for this subquery
        val rowsId = s"rows-$fileNumber"

        val subQueryContent: Seq[Node] =
          if (subQuery.rows.isEmpty) {
            Seq(<p>This subquery has returned 0 rows.</p>)
          } else {
            val rowsContainerId = s"rows-container-$rowsId"
            val rowsHtml = renderRowsAsTable(subQuery.rows, rowsId)

            Seq(
              <button class="toggle-button" onclick={s"toggleRowsVisibility('$rowsContainerId');"}>View Rows</button>,
              <div id={rowsContainerId} class="rows-container" style="display:none;">
                {Unparsed(rowsHtml)}
              </div>
            )
          }

        Seq(
          <li class="sub-query-list" data-mapping-index={mappingIndex.toString}>
            <div class="subquery-section" id={"query-" + mappingIndex}>
              <textarea class="sql-query" data-query={subQuery.subQueryString} data-mapping-index={mappingIndex.toString}>
                {subQuery.subQueryString}
              </textarea>{subQueryContent}
            </div>
          </li>
        )
      }


      val planXmlString = sc.getPlanTree
      val planXmlElement = scala.xml.XML.loadString(planXmlString)
      val planTreeHtml = renderPlanNodeTreeXml(planXmlElement, availableMappings)
      println(planXmlString)
      println(planXmlElement)

      val content = <div>
        <div class="description">
          <p>On the left side of the screen, you can view the original query that was submitted to Spark. On the right side, the corresponding execution plan tree is displayed. Nodes highlighted in green are interactive; clicking on them will direct you to the respective subqueries.</p>
        </div>
        <div class="layout-container">
          <div class="original-query-container">
            <h2>Original Query:</h2>
            <textarea id="original-query" class="sql-query">
              {originalQuery}
            </textarea>
          </div>
          <div class="plan-tree-container">
            <h3>Plan Tree:</h3>
            <ul class="plan-tree">
              {planTreeHtml}
            </ul>
          </div>
        </div>
        <h3>Subqueries:</h3>
        <ul>
          {subqueriesContent}
        </ul>
      </div>
      val script =
        """
         <!-- CodeMirror CSS -->
        <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/codemirror@5/lib/codemirror.css">

        <script src="https://cdn.jsdelivr.net/npm/sql-formatter@3.1.0/dist/sql-formatter.min.js"></script>


        <!-- CodeMirror JavaScript -->
        <script src="https://cdn.jsdelivr.net/npm/codemirror@5/lib/codemirror.js"></script>

        <!-- SQL Mode - required for SQL syntax highlighting -->
        <script src="https://cdn.jsdelivr.net/npm/codemirror@5/mode/sql/sql.js"></script>
        <script src="https://cdn.jsdelivr.net/npm/sql-formatter@3.1.0/dist/sql-formatter.min.js"></script>



        <script>
    function initializeCodeMirror() {
          document.querySelectorAll('.sql-query').forEach(function (el) {
            const formattedSQL = sqlFormatter.format(el.value);
            el.value = formattedSQL;
            const editor = CodeMirror.fromTextArea(el, {
              mode: "text/x-sql",
              theme: "default",
              lineNumbers: false,
              readOnly: true,
              lineWrapping: true
            });

            editor.on('refresh', function() {
              // Directly remove or modify the inline height style if it exists
              const cmWrapper = editor.getWrapperElement();
              cmWrapper.style.height = ''; // Clear any inline height style

              // Calculate the height based on the content
              var totalHeight = editor.getScrollInfo().height;
              // Set the height of the editor
              editor.setSize(null, totalHeight + 'px');
            });

            // Initial refresh to adjust size
            setTimeout(function() {
              editor.refresh();
            }, 0);
          });
        }

        function handleClick(mappingIndex) {
                 console.log("I was in handle click");
                  console.log(mappingIndex);
                 const mappingIndexAttribute = 'data-mapping-index';
                 // Use mappingIndex to find the subquery section with the matching mapping index
                 const subQuerySection = document.getElementById("query-" + mappingIndex);
                 console.log(subQuerySection);
                 if (subQuerySection) {
                  console.log("I was inside if");
                   subQuerySection.scrollIntoView({ behavior: "smooth", block: "start" });
                   // Optionally highlight the subquery section or perform other visual cues
                   subQuerySection.classList.add("highlight");
                   setTimeout(() => subQuerySection.classList.remove("highlight"), 2000);
                 }
               }
        document.addEventListener("DOMContentLoaded", function() {
            initializeCodeMirror();

            // Setup "Show More" functionality
            document.querySelectorAll(".show-more").forEach(button => {
                button.addEventListener("click", function() {
                    let rowsId = this.getAttribute("data-rows-id");
                    showMoreRows(rowsId);
                });
            });

            // Toggle visibility of rows containers
            document.querySelectorAll(".toggle-button").forEach(button => {
                button.addEventListener("click", function() {
                    let targetId = this.getAttribute("data-target");
                    toggleRowsVisibility(targetId);
                });
            });
        });


        function showMoreRows(rowsId) {
            let allRows = document.querySelectorAll(`#table-${rowsId} tr`);
            let currentlyHiddenRows = Array.from(allRows).filter(row => row.classList.contains('hidden-row'));

            // Show the next 5 hidden rows
            currentlyHiddenRows.slice(0, 5).forEach(row => {
                row.classList.remove('hidden-row'); // If using CSS classes to hide rows
                // row.style.display = 'table-row'; // Use this line instead if you're showing rows by changing the display style
            });

            // Update the condition for hiding the "Show More" button
            let newHiddenRowCount = Array.from(allRows).filter(row => row.classList.contains('hidden-row')).length;
            if (newHiddenRowCount === 0) {
                document.querySelector(`button[data-rows-id="${rowsId}"]`).style.display = 'none';
            }
        }




        // Toggles the visibility of the rows container
        function toggleRowsVisibility(elementId) {
            var element = document.getElementById(elementId);
            element.style.display = element.style.display === 'none' ? 'block' : 'none';
        }

        </script>

         """

      val style =
        """
        <style>
         .sub-query-list{
         list-style-type: none;
         }

         .CodeMirror-wrap{
         height: fit-content !important;
         }
        .node-button {
          margin-left: 5px !important;
          border: 1px solid #ccc; /* subtle border */
          border-radius: 8px; /* rounded corners */
          padding: 4px 8px; /* some padding */
          cursor: pointer; /* cursor indicates clickable item */
          outline: none; /* removes the outline to make it look more like a span */
          margin: 2px; /* adjust margin as necessary */
          font-size: inherit; /* inherits font size from parent */
          font-weight: bold;
        }

        .node-button:hover,
        .node-button:focus {
          background-color: #e7e7e7; /* slightly darker background on hover/focus */
          border-color: #aaa; /* darker border to stand out */
        }

        /* Adjust the spacing for the button to align with tree indentation */
        .plan-tree li > button {
          margin-left: -20px;
          padding-left: 25px; /* adds space to the left of button text */
        }


        .node-text {
          display: inline-block; /* Or 'block' if you prefer the text to be on a separate line */
          padding: 4px 8px; /* Adjust padding to your preference */
          border-radius: 8px; /* Rounded corners */
          margin: 2px; /* Spacing around the text */
          font-weight: bold; /* Optional: if you want the node text to be bold */
        }

        .node-mapped > .node-button {
          background-color: #e0ffe0; /* Light green background */
        }

        .node-unmapped > .node-button {
          background-color: #ffe0e0; /* Light red background */
        }


        .layout-container {
                 display: flex;
                 justify-content: space-between; /* Adjusts space between child elements */
                 align-items: start; /* Aligns items to the start of the flex container */
                 margin-bottom: 20px; /* Adds some space below the container */
               }

        .original-query-container, .plan-tree-container {
          flex: 1; /* Each child will take up equal amount of space */
          padding: 10px; /* Adds some padding around each child */
        }
        .highlight {
          background-color: yellow; /* Example highlighting style */
          transition: background-color 2s; /* Smooth transition for the background color */
        }

        .plan-tree, .plan-tree ul {
              list-style-type: none;
            }
            .plan-tree ul {
              margin-left: 20px;
            }
            .plan-tree li {
              margin: 5px 0;
              position: relative;
            }
            .plan-tree li::before, .plan-tree li::after {
              content: '';
              position: absolute;
              left: -20px;
            }
            .plan-tree li::before {
              border-top: 1px solid #000;
              top: 10px;
              width: 20px;
              height: 0;
            }
            .plan-tree li::after {
              border-left: 1px solid #000;
              height: 100%;
              width: 0px;
              top: 2px;
            }
            .plan-tree li:last-child::after {
              display: none;
            }
       .CodeMirror {
          width: 700px;
          height: auto; /* Adjust height automatically to the content */
          border: 1px solid #ddd;
        }

        .CodeMirror-scroll {
          overflow-x: auto; /* Horizontal scrollbar only if needed */
        }

        .CodeMirror-readOnly {
          background-color: #f5f5f5; /* Your styles for read-only state */
        }

        .hidden-row { display: none; }
        .show-more { /* Button styles */ }
          .sql-keyword { font-weight: bold; color: #007bff; }
          .sql-function { font-weight: bold; color: #d63384; }
          .toggle-button {
            background-color: #007bff;
            color: white;
            padding: 6px 12px;
            margin: 5px;
            border: none;
            cursor: pointer;
            border-radius: 4px;
            font-size: 14px;
          }
          .toggle-button:hover {
            background-color: #0056b3;
          }
          .pretty-table {
          border-collapse: collapse;
          table-layout: auto; /* This allows the table to determine column width based on content */
          width: 100%; /* Ensures the table uses the full width of its container */
        }

        .pretty-table th,
        .pretty-table td {
          border: 1px solid #ddd;
          text-align: left;
          padding: 8px;
          width: auto; /* Allows the cell to adjust its width based on content */
        }

        .pretty-table th {
          background-color: #f2f2f2;
        }

        /* Optional: Ensures table does not stretch too wide if content is short */
        .pretty-table-container {
          display: inline-block; /* Makes the container only as wide as it needs to be */
          width: auto;
        }

        </style>
        """

      // Combine content with style and script
      val combinedContent: scala.xml.Node = <div>
        {content}
      </div>
      UIUtils.headerSparkPage(request, "DeSQL", Seq(Unparsed(style), combinedContent, Unparsed(script)), parent, useDataTables = true)
    } else {
      // Generate simple message content and auto-refresh script
      val message = <div>Data is still processing, please wait...</div>
      val autoRefreshScript = generateAutoRefreshScript(true)

      // Combine message and auto-refresh script
      UIUtils.headerSparkPage(request, "DeSQL", Seq(autoRefreshScript, message), parent, useDataTables = true)
    }
  }

  private def generateAutoRefreshScript(continueRefreshing: Boolean = false): Node = {
    if (continueRefreshing) {
      Unparsed(
        """
           <script>
             setTimeout(function() {
               window.location.reload();
             }, 2000);
           </script>
           """
      )
    } else {
      Unparsed("")
    }
  }

  // Helper method to render rows as an HTML table with columns
  // Helper method to render rows as an HTML table with columns
  private def renderRowsAsTable(rows: Seq[String], rowsId: String): String = {
    val hasMoreRows = rows.length > 5

    // Combine all rows into a single sequence with an index, and determine which ones to hide
    val tableRows = rows.zipWithIndex.map { case (row, index) =>
      // Split row into columns, trim each value, and remove any leading/trailing brackets if present
      val columns = row.split(",").map { value =>
        val trimmedValue = value.trim
        // Remove leading and trailing brackets
        val cleanedValue = trimmedValue.stripPrefix("[").stripSuffix("]")
        s"<td>$cleanedValue</td>"
      }.mkString
      val style = if (index >= 5) " class='hidden-row'" else "" // Apply class to hide rows beyond the first 5
      s"<tr$style>$columns</tr>"
    }.mkString

    // The button HTML is kept separate to ensure it's placed outside the 'pretty-table-container' div
    val showMoreButtonHtml = if (hasMoreRows) s"""<div><button class="show-more" data-rows-id="$rowsId">Show More</button></div>""" else ""

    // Return the complete HTML string for the table and the show more button if applicable
    s"""
   <div class="pretty-table-container">
     <table class="pretty-table" id="table-$rowsId">
       <tbody>
         $tableRows
       </tbody>
     </table>
   </div>
   $showMoreButtonHtml
   """
  }

}
