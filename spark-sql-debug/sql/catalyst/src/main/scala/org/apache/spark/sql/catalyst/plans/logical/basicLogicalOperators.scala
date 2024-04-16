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

package org.apache.spark.sql.catalyst.plans.logical


import scala.collection.mutable
import org.apache.spark.sdb._
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.AliasIdentifier
import org.apache.spark.sql.catalyst.analysis.{MultiInstanceRelation, UnresolvedAlias, UnresolvedAttribute, UnresolvedFunction, UnresolvedHaving, UnresolvedRelation}
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable}
import org.apache.spark.sql.catalyst.expressions.{GenericRowWithSchema, _}
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, AggregateFunction}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.physical.{HashPartitioning, Partitioning, RangePartitioning, RoundRobinPartitioning}
import org.apache.spark.sql.catalyst.util.truncatedString
import org.apache.spark.sql.connector.catalog.Identifier
import org.apache.spark.sql.types._
import org.apache.spark.util.random.RandomSampler
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.rdd.RDD


import scala.collection.mutable.ArrayBuffer

/**
 * When planning take() or collect() operations, this special node that is inserted at the top of
 * the logical plan before invoking the query planner.
 *
 * Rules can pattern-match on this node in order to apply transformations that only take effect
 * at the top of the logical query plan.
 */
case class ReturnAnswer(child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
}

/**
 * This node is inserted at the top of a subquery when it is optimized. This makes sure we can
 * recognize a subquery as such, and it allows us to write subquery aware transformations.
 *
 * @param correlated flag that indicates the subquery is correlated, and will be rewritten into a
 *                   join during analysis.
 */
case class Subquery(child: LogicalPlan, correlated: Boolean) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output
}

object Subquery {
  def fromExpression(s: SubqueryExpression): Subquery =
    Subquery(s.plan, SubqueryExpression.hasCorrelatedSubquery(s))
}

trait SubQueryVisitor {
  def visit(project: Project, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(filterList: ArrayBuffer[SubQueryStorage], subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(subqueryAlias: SubqueryAlias, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(logicalPlan: LogicalPlan, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(unresolvedRelation: UnresolvedRelation, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  //def visit(logicalRelation: LogicalRelation, subQueryList : ArrayBuffer[SubQueryStorage]) : ArrayBuffer[SubQueryStorage]
  def visit(join: Join, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(aggregate: Aggregate, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(unresolvedHaving: UnresolvedHaving, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(sort: Sort, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(unresolvedAttribute: UnresolvedAttribute, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(attributeReference: AttributeReference, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(equalTo: EqualTo, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(literal: Literal, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(and: And, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(or: Or, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(not: Not, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(expression: Expression, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(equalNullSafe: EqualNullSafe, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(like: Like, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(lessThan: LessThan, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(lessThanOrEqual: LessThanOrEqual, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(greaterThan: GreaterThan, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(greaterThanOrEqual: GreaterThanOrEqual, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(subqueryExpression: SubqueryExpression, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(inSubquery: InSubquery, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(cast: Cast, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]

  def visit(globalLimit: GlobalLimit, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]
}

class SubQueryGeneratorVisitor(var enabled: Boolean = false) extends SubQueryVisitor {
  def visit(project: Project, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    project.setMappingIndex(r.nextInt(10000))
    var projectionList = new ArrayBuffer[String]()
    for (projection <- project.projectList) {
      if (projection.isInstanceOf[UnresolvedAlias]) {
        if (projection.asInstanceOf[UnresolvedAlias].child.isInstanceOf[AggregateExpression]) {
          projectionList.append(projection.asInstanceOf[UnresolvedAlias].child.asInstanceOf[AggregateExpression].aggregateFunction.toString())
        } else if (projection.asInstanceOf[UnresolvedAlias].child.isInstanceOf[UnresolvedFunction]) {
          projectionList.append(projection.asInstanceOf[UnresolvedAlias].child.toString())
        }
      } else if (projection.isInstanceOf[UnresolvedAttribute]) {
        projectionList.append(projection.toString())
      } else {
        projectionList.append(projection.toString())
      }
    }
    if(project.child.isInstanceOf[Filter]){
      if(project.child.asInstanceOf[Filter].child.isInstanceOf[SubqueryAlias]) {
        project.child.asInstanceOf[Filter].child.asInstanceOf[SubqueryAlias].child.setMappingIndex(project.getMappingIndex())
      }
    }
    var projectedList = projectionList.mkString(",")
    subQueryList.append(new SubQueryStorage("Select " + projectedList, project.getMappingIndex))
    subQueryList
  }


  def visit(filterList: ArrayBuffer[SubQueryStorage], subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var filterDistinctList = filterList.distinct
    var index = subQueryList.size - 1
    for (filterValue <- filterDistinctList) {
      subQueryList.append(new SubQueryStorage(subQueryList(index).subQueryString + " where " + filterValue.subQueryString, filterValue.subQueryIndex,filterValue.regenFlag))
    }
    subQueryList
  }

  def visit(subqueryAlias: SubqueryAlias, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var tableIdentifier = subqueryAlias.identifier.toString
    var genericQuery = subQueryList(0).subQueryString + " from " + tableIdentifier
    subQueryList(0).subQueryString = genericQuery
    subQueryList
  }

  def visit(logicalPlan: LogicalPlan, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    subQueryList
  }

  def visit(unresolvedRelation: UnresolvedRelation, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var tableIdentifier = unresolvedRelation.name
    var genericQuery = subQueryList(0).subQueryString + " from " + tableIdentifier
    subQueryList(0).subQueryString = genericQuery
    subQueryList
  }

  //def visit(logicalRelation: LogicalRelation, subQueryList : ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
  //var tableIdentifier = logicalRelation.relation.asInstanceOf[HadoopFsRelation].inputFiles(0).split("/").takeRight(1)
  //tableIdentifier = if (tableIdentifier.indexOf(".") > 0) tableIdentifier.substring(0, tableIdentifier.lastIndexOf("."))
  //var genericQuery = subQueryList(0).subQueryString + " from " + tableIdentifier
  //subQueryList(0).subQueryString = genericQuery
  //  subQueryList
  //  }


  def visit(join: Join, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    enabled = true
    var resultQueryBuffer = new ArrayBuffer[SubQueryStorage]
    val r = scala.util.Random
    join.setMappingIndex(r.nextInt(10000))

    var genericLeftMappingIndex = r.nextInt(10000)
    var genericRightMappingIndex = r.nextInt(10000)
    var joinLeftTableFieldIndex = join.left.schema.fieldIndex(join.references.head.name)
    var joinRightTableFieldIndex = join.right.schema.fieldIndex(join.references.last.name)
    var genericQuery = subQueryList(0).subQueryString + " from " + join.left.asInstanceOf[SubqueryAlias].identifier.toString + " " + join.joinType.toString + " join " + join.right.asInstanceOf[SubqueryAlias].identifier.toString + " on" + " " + join.condition

    resultQueryBuffer.append(new SubQueryStorage(genericQuery, join.getMappingIndex(),true,joinLeftTableFieldIndex,joinRightTableFieldIndex))
    var leftOutputSet = join.left.asInstanceOf[SubqueryAlias].outputSet
    var rightOutputSet = join.right.asInstanceOf[SubqueryAlias].outputSet
    var rightQuery = "select * from " + join.right.asInstanceOf[SubqueryAlias].identifier
    var leftQuery = "select * from " + join.left.asInstanceOf[SubqueryAlias].identifier

    var rightResultList = new ArrayBuffer[SubQueryStorage]
    var leftResultList = new ArrayBuffer[SubQueryStorage]
    var filterList = subQueryList.tail
    for (str <- filterList) {
      var trimmedList: List[String] = str.subQueryString.split("AND").map(_.trim).toList
      var requiredCondition = trimmedList.last
      var conditionMappingIndex = str.subQueryIndex
      var columnName = requiredCondition.substring(0, requiredCondition.indexOf("#"))
      for (x <- leftOutputSet) {
        if (x.name == columnName) {
          if (leftResultList.size < 1) {
            leftResultList.append(new SubQueryStorage(leftQuery + " where " + requiredCondition, conditionMappingIndex))
          } else {
            leftResultList.append(new SubQueryStorage(leftResultList.last.subQueryString + " AND " + requiredCondition, conditionMappingIndex))
          }
        }
      }
      for (x <- rightOutputSet) {
        if (x.name == columnName) {
          if (rightResultList.size < 1) {
            rightResultList.append(new SubQueryStorage(rightQuery + " where " + requiredCondition, conditionMappingIndex))
          } else {
            rightResultList.append(new SubQueryStorage(rightResultList.last.subQueryString + " AND " + requiredCondition, conditionMappingIndex))
          }
        }
      }
    }
    resultQueryBuffer.prependAll(rightResultList)
    resultQueryBuffer.prependAll(leftResultList)
    join.left.asInstanceOf[SubqueryAlias].child.setMappingIndex(genericLeftMappingIndex)
    join.right.asInstanceOf[SubqueryAlias].child.setMappingIndex(genericRightMappingIndex)
    resultQueryBuffer.prepend(new SubQueryStorage(rightQuery, join.right.asInstanceOf[SubqueryAlias].child.getMappingIndex))
    resultQueryBuffer.prepend(new SubQueryStorage(leftQuery, join.left.asInstanceOf[SubqueryAlias].child.getMappingIndex))
    resultQueryBuffer
  }

  def visit(aggregate: Aggregate, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var newSubQueryList = subQueryList
    if(subQueryList.size > 0){
      newSubQueryList = subQueryList.drop(1)
    }
    enabled = true
    val r = scala.util.Random
    aggregate.setMappingIndex(r.nextInt(10000))
    /*if (aggregate.child.isInstanceOf[Filter]) {
      var groupingList = aggregate.groupingExpressions.mkString(",")
      var groupByClause = subQueryList.last.subQueryString + " Group by " + groupingList
      subQueryList.append(new SubQueryStorage(groupByClause.toString, aggregate.getMappingIndex))
      subQueryList
    } else {*/
    var groupByQuery = new StringBuilder("Select ")
    if (aggregate.asInstanceOf[Aggregate].aggregateExpressions(0).isInstanceOf[Attribute]) {
      groupByQuery ++= aggregate.asInstanceOf[Aggregate].aggregateExpressions(0).toString()
    }
    else {
      groupByQuery ++= aggregate.asInstanceOf[Aggregate].aggregateExpressions(0).asInstanceOf[Alias].name.toString()
    }

    for (aggregateList <- aggregate.asInstanceOf[Aggregate].aggregateExpressions.tail) {
      if (aggregateList.isInstanceOf[Attribute]) {
        groupByQuery ++= "," + aggregateList.toString()
      }
      else {
        groupByQuery ++= "," + aggregateList.asInstanceOf[Alias].child.toString()
      }
    }
    groupByQuery ++= " from " + aggregate.child.asInstanceOf[SubqueryAlias].identifier.toString()
    aggregate.child.asInstanceOf[SubqueryAlias].child.setMappingIndex(r.nextInt(10000))
    newSubQueryList.append(new SubQueryStorage("Select " + aggregate.groupingExpressions.head.asInstanceOf[AttributeReference].name + ", " + aggregate.aggregateExpressions.head.name + "from " + aggregate.child.asInstanceOf[SubqueryAlias].identifier.toString(), aggregate.child.asInstanceOf[SubqueryAlias].child.getMappingIndex))
    if (!aggregate.groupingExpressions.isEmpty) {
      newSubQueryList.append(new SubQueryStorage(groupByQuery.toString, 111111))
      var groupingList = aggregate.groupingExpressions.mkString(",")
      groupByQuery ++= " Group by " + groupingList
    }
    newSubQueryList.append(new SubQueryStorage(groupByQuery.toString, aggregate.getMappingIndex))
    newSubQueryList
    //}
  }

  def visit(unresolvedHaving: UnresolvedHaving, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var havingList = unresolvedHaving.havingCondition.toString().split("AND")
    subQueryList.append(new SubQueryStorage(subQueryList(subQueryList.size - 1) + " having " + havingList(0), 0))
    for (havingValue <- havingList.tail) {
      subQueryList.append(new SubQueryStorage(subQueryList(subQueryList.size - 1) + " AND " + havingValue, 0))
    }
    subQueryList
  }

  def visit(sort: Sort, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    sort.setMappingIndex(r.nextInt(10000))
    var sortList = sort.order
    var direction = if (sortList(0).direction.toString == "Ascending") "ASC" else "DESC"
    subQueryList.append(new SubQueryStorage(subQueryList(subQueryList.size - 1).subQueryString + " order by " + sortList(0).child.toString + " " + direction, sort.getMappingIndex))
    for (sortValue <- sortList.tail) {
      direction = if (sortValue.direction.toString == "Ascending") "ASC" else "DESC"
      subQueryList.append(new SubQueryStorage(subQueryList(subQueryList.size - 1).subQueryString + "," + sortValue.child.toString + " " + direction, sort.getMappingIndex))
    }
    subQueryList
  }

  def visit(unresolvedAttribute: UnresolvedAttribute, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var attributeName = new ArrayBuffer[SubQueryStorage]()
    attributeName.append(new SubQueryStorage(unresolvedAttribute.toString, 0))
    attributeName
  }

  def visit(attributeReference: AttributeReference, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var attributeName = new ArrayBuffer[SubQueryStorage]()
    attributeName.append(new SubQueryStorage(attributeReference.toString, 0))
    attributeName
  }

  def visit(equalTo: EqualTo, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    equalTo.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    var valueWithOperator = subQueryList(0).subQueryString + " " + equalTo.symbol + " " + subQueryList(1).subQueryString
    result.append(new SubQueryStorage(valueWithOperator, equalTo.getMappingIndex))
    result
  }

  def visit(like: Like, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    like.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    var valueWithOperator = subQueryList(0).subQueryString + " like " + subQueryList(1).subQueryString
    result.append(new SubQueryStorage(valueWithOperator, like.getMappingIndex))
    result
  }

  def visit(and: And, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    and.setMappingIndex(r.nextInt(10000))
    subQueryList(subQueryList.size - 1).subQueryString = subQueryList(subQueryList.size - 2).subQueryString + " " + and.sqlOperator + " " + subQueryList(subQueryList.size - 1).subQueryString
    subQueryList(subQueryList.size - 1).subQueryIndex = subQueryList(subQueryList.size - 1).subQueryIndex
    subQueryList
  }

  def visit(literal: Literal, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var attributeName = new ArrayBuffer[SubQueryStorage]()
    attributeName.append(new SubQueryStorage(literal.toString, 0))
    attributeName
  }

  def visit(or: Or, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    enabled = true
    val r = scala.util.Random
    //    val start = 11000
    //    val end = 12000
    or.setMappingIndex(r.nextInt(10000))
    //subQueryList(subQueryList.size - 2).subQueryIndex = start + r.nextInt((end-start) + 1)
    subQueryList(subQueryList.size - 1).subQueryString = subQueryList(subQueryList.size - 2).subQueryString + " " + or.sqlOperator + " " + subQueryList(subQueryList.size - 1).subQueryString
    subQueryList(subQueryList.size - 1).subQueryIndex = or.getMappingIndex
    subQueryList(subQueryList.size - 2).regenFlag = true
    subQueryList
  }

  def visit(expression: Expression, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    subQueryList
  }

  def visit(equalNullSafe: EqualNullSafe, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    equalNullSafe.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    var valueWithOperator = subQueryList(0).subQueryString + " " + equalNullSafe.symbol + " " + subQueryList(1).subQueryString
    result.append(new SubQueryStorage(valueWithOperator, equalNullSafe.getMappingIndex))
    result
  }

  def visit(lessThan: LessThan, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    lessThan.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    var valueWithOperator = subQueryList(0).subQueryString + " " + lessThan.symbol + " " + subQueryList(1).subQueryString
    result.append(new SubQueryStorage(valueWithOperator, lessThan.getMappingIndex))
    result
  }

  def visit(lessThanOrEqual: LessThanOrEqual, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    lessThanOrEqual.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    var valueWithOperator = subQueryList(0).subQueryString + " " + lessThanOrEqual.symbol + " " + subQueryList(1).subQueryString
    result.append(new SubQueryStorage(valueWithOperator, lessThanOrEqual.getMappingIndex))
    result
  }

  def visit(greaterThan: GreaterThan, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    greaterThan.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    var valueWithOperator = subQueryList(0).subQueryString + " " + greaterThan.symbol + " " + subQueryList(1).subQueryString
    result.append(new SubQueryStorage(valueWithOperator, greaterThan.getMappingIndex))
    result
  }

  def visit(greaterThanOrEqual: GreaterThanOrEqual, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    greaterThanOrEqual.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    var valueWithOperator = subQueryList(0).subQueryString + " " + greaterThanOrEqual.symbol + " " + subQueryList(1).subQueryString
    result.append(new SubQueryStorage(valueWithOperator, greaterThanOrEqual.getMappingIndex))
    result
  }

  def visit(subqueryExpression: SubqueryExpression, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    subqueryExpression.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    if (subqueryExpression.isInstanceOf[ScalarSubquery]) {
      result.append(new SubQueryStorage("(" + subQueryList(subQueryList.size - 1).subQueryString + ")", subqueryExpression.getMappingIndex))
    }
    result
  }

  def visit(inSubquery: InSubquery, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    inSubquery.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    result.append(new SubQueryStorage(subQueryList(0).subQueryString + " IN " + "( " + subQueryList(1).subQueryString + " )", inSubquery.getMappingIndex()))
    result
  }

  def visit(not: Not, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    val r = scala.util.Random
    not.setMappingIndex(r.nextInt(10000))
    var result = new ArrayBuffer[SubQueryStorage]()
    result.append(new SubQueryStorage(" NOT " + subQueryList(0).subQueryString, not.getMappingIndex))
    result
  }

  def visit(cast: Cast, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    subQueryList
  }

  def visit(globalLimit: GlobalLimit, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    var limitVal = globalLimit.limitExpr.asInstanceOf[Literal].value.toString
    var limitQuery = subQueryList.last + " limit " + limitVal
    subQueryList.append(new SubQueryStorage(limitQuery, 12345))
    subQueryList
  }
}

trait SubQueryExist {
  def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage]
}

trait DataRegeneratorVisitorTrait {
  def visit(project: Project, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(logicalPlan: LogicalPlan, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(join: Join, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(aggregate: Aggregate, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int , joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(equalTo: EqualTo, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int , joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(or: Or, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int , joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(lessThan: LessThan, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(lessThanOrEqual: LessThanOrEqual, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(greaterThan: GreaterThan, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(greaterThanOrEqual: GreaterThanOrEqual, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)]

  def visit(expression: Expression, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)]
}

class DataRegeneratorVisitor extends DataRegeneratorVisitorTrait with Logging{
  def visit(project: Project, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    if(project.child.isInstanceOf[Filter]) {
      if(project.child.asInstanceOf[Filter].child.isInstanceOf[Join]){
        val mappingIndexForJoin = project.child.asInstanceOf[Filter].child.asInstanceOf[Join].getMappingIndex()
        return existingData.filter(x => x._3 != mappingIndexForJoin)
      }else {
        val projectMappingIndex = project.getMappingIndex()
        val filteredData = existingData.filter(x => x._3 == projectMappingIndex)
        return filteredData
      }
    }else if(project.child.isInstanceOf[Join]) {
      val mappingIndex = project.child.asInstanceOf[Join].getMappingIndex()
      return existingData.filter(x => x._3 != mappingIndex)
    }
    return existingData
  }

  def visit(logicalPlan: LogicalPlan, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int , joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    existingData
  }

  def visit(filterList: ArrayBuffer[SubQueryStorage], existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int , joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    existingData
  }


  def visit(join: Join, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val t1 = System.nanoTime()
    val leftTableMappingIndex = join.left.asInstanceOf[SubqueryAlias].child.getMappingIndex()
    val rightTableMappingIndex = join.right.asInstanceOf[SubqueryAlias].child.getMappingIndex()
    val attributNameStr = join.condition.mkString(",").split("#")
    val attributeName = attributNameStr(0).substring(1,attributNameStr(0).length)
    val leftTableReqDataRDD = existingData.filter(x => x._3 == leftTableMappingIndex)
    val rightTableReqDataRDD = existingData.filter(x => x._3 == rightTableMappingIndex)
    val leftReqData = leftTableReqDataRDD.map(x => x._1)
    val rightReqData = rightTableReqDataRDD.map(x => x._1)
    val leftAttributeName = join.asInstanceOf[Join].validConstraints.asInstanceOf[ExpressionSet].originals(0).asInstanceOf[EqualTo].left.asInstanceOf[AttributeReference].name
    val rightAttributeName  = join.asInstanceOf[Join].validConstraints.asInstanceOf[ExpressionSet].originals(0).asInstanceOf[EqualTo].right.asInstanceOf[AttributeReference].name
//    val leftAttributIndex = leftReqData.first().fieldIndex(leftAttributeName)
    val leftAttributIndex = joinLeftTableFieldIndex
//    val rightAttributIndex = rightReqData.first().fieldIndex(rightAttributeName)
    val rightAttributIndex = joinRightTableFieldIndex
    val leftTableForJoin = leftReqData.map(z => z.toString.substring(1, z.toString.length - 1))
    val rightTableForJoin = rightReqData.map(z => z.toString.substring(1, z.toString.length - 1))
    val leftPairRDD = leftTableForJoin.map(x => (x.replaceAll("\\s", "").split(",")(leftAttributIndex), x))
    val rightPairRDD = rightTableForJoin.map(x => (x.replaceAll("\\s", "").split(",")(rightAttributIndex), x))
    val resultRDD = leftPairRDD.join(rightPairRDD)
    val mappedResultRDD = resultRDD.map(x => x._2)
    val mappingIndex = join.getMappingIndex()
    //val schemaVal = leftTableReqDataRDD.first()._5 + rightTableReqDataRDD.first()._5.substring(0,rightTableReqDataRDD.first()._5.length - 1)
    val joinSchema = join.schema
    val joinSchenaString = join.schema.toString()
    val resultTuplesRDD = mappedResultRDD.map(x => {
      new Tuple5(new GenericRowWithSchema(Array(x.toString()),joinSchema), "|",mappingIndex,"|",joinSchenaString)
    })

    val duration = (System.nanoTime() - t1) / 1e9d
    logInfo(s"{Checking point one")
    logInfo(s"$duration")
    resultTuplesRDD
  }

  def visit(aggregate: Aggregate, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int , joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val schemaValue = existingData.first()._5
    var sumColumn = existingData.map(x => x._1.toString.substring(1, x._1.toString.length - 1).toInt)
    val aggRes = sumColumn.map(s=> (1, s)).reduceByKey(_+_).map(_._2)
    val row = new GenericRowWithSchema(Array(aggRes.first().toString), aggregate.schema)
    val results = aggRes.map(x => {
      new Tuple5(row, "|", 111111, "|", schemaValue)
    })
    results
  }

  def visit(equalTo: EqualTo, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val attributeName = equalTo.left.asInstanceOf[Cast].child.asInstanceOf[AttributeReference].name
    val attribueValue = equalTo.right.asInstanceOf[Literal].value
    val reqData = existingData.map(x => x._1)
    val attributIndex = reqData.first().fieldIndex(attributeName)
    val results = reqData.filter(x => x.get(attributIndex).toString.toInt == attribueValue.toString.toInt)
    val schemaValInString = existingData.first()._5
    val mappingIndex = equalTo.getMappingIndex()
    val resultRDD = results.map(x => {
      new Tuple5(x, "|", mappingIndex, "|", schemaValInString)
    })
    resultRDD
  }

  def visit(or: Or, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val orMappingIndex = or.getMappingIndex()
    val resultRDD = existingData.map(x => {
      new Tuple5(x._1, "|", orMappingIndex, "|", x._5)
    })
    resultRDD
  }

  def visit(lessThan: LessThan, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val attributeName = lessThan.left.asInstanceOf[Cast].child.asInstanceOf[AttributeReference].name
    val attribueValue = lessThan.right.asInstanceOf[Literal].value
    val reqData = existingData.map(x => x._1)
    val attributIndex = reqData.first().fieldIndex(attributeName)
    val results = reqData.filter(x => x.get(attributIndex).toString.toInt < attribueValue.toString.toInt)
    val schemaValInString = existingData.first()._5
    val mappingIndex = lessThan.getMappingIndex()
    val resultRDD = results.map(x => {
      new Tuple5(x, "|", mappingIndex, "|", schemaValInString)
    })
    resultRDD
  }

  def visit(lessThanOrEqual: LessThanOrEqual, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val attributeName = lessThanOrEqual.left.asInstanceOf[Cast].child.asInstanceOf[AttributeReference].name
    val attribueValue = lessThanOrEqual.right.asInstanceOf[Literal].value
    val reqData = existingData.map(x => x._1)
    val attributIndex = reqData.first().fieldIndex(attributeName)
    val results = reqData.filter(x => x.get(attributIndex).toString.toInt <= attribueValue.toString.toInt)
    val schemaValInString = existingData.first()._5
    val mappingIndex = lessThanOrEqual.getMappingIndex()
    val resultRDD = results.map(x => {
      new Tuple5(x, "|", mappingIndex, "|", schemaValInString)
    })
    resultRDD
  }

  def visit(greaterThan: GreaterThan, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val attributeName = greaterThan.left.asInstanceOf[Cast].child.asInstanceOf[AttributeReference].name
    val attribueValue = greaterThan.right.asInstanceOf[Literal].value
    val reqData = existingData.map(x => x._1)
    val attributIndex = reqData.first().fieldIndex(attributeName)
    val results = reqData.filter({x =>
      if(x != null){ x.get(attributIndex).toString.toInt > attribueValue.toString.toInt}
      else {false}
    })
    val schemaValInString = existingData.first()._5
    val mappingIndex = greaterThan.getMappingIndex()
    val resultRDD = results.map(x => {
      new Tuple5(x, "|", mappingIndex, "|", schemaValInString)
    })
    resultRDD
  }

  def visit(greaterThanOrEqual: GreaterThanOrEqual, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val attributeName = greaterThanOrEqual.left.asInstanceOf[Cast].child.asInstanceOf[AttributeReference].name
    val attribueValue = greaterThanOrEqual.right.asInstanceOf[Literal].value
    val reqData = existingData.map(x => x._1)
    val attributIndex = reqData.first().fieldIndex(attributeName)
    val results = reqData.filter(x => x.get(attributIndex).toString.toInt >= attribueValue.toString.toInt)
    val schemaValInString = existingData.first()._5
    val mappingIndex = greaterThanOrEqual.getMappingIndex()
    val resultRDD = results.map(x => {
      new Tuple5(x, "|", mappingIndex, "|", schemaValInString)
    })
    resultRDD
  }

  def visit(expression: Expression, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    existingData
  }

}

trait EnableRegeneration {
  def acceptRegeneration(dataRegeneratorVisitorTrait: DataRegeneratorVisitorTrait, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int, joinRightTableFieldIndex: Int ): RDD[(GenericRowWithSchema, String, Int, String, String)]
}

case class Project(var projectList: Seq[NamedExpression], child: LogicalPlan)
  extends OrderPreservingUnaryNode with SubQueryExist with EnableRegeneration {

  override def output: Seq[Attribute] = projectList.map(_.toAttribute)

  override def maxRows: Option[Long] = child.maxRows

  override lazy val resolved: Boolean = {
    val hasSpecialExpressions = projectList.exists(_.collect {
      case agg: AggregateExpression => agg
      case generator: Generator => generator
      case window: WindowExpression => window
    }.nonEmpty
    )

    !expressions.exists(!_.resolved) && childrenResolved && !hasSpecialExpressions
  }

  override lazy val validConstraints: Set[Expression] =
    getAllValidConstraints(projectList)

  override def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    subQueryVisitor.visit(this, subQueryList)
    this.child.accept(subQueryVisitor, subQueryList)
  }

  override def acceptRegeneration(dataRegeneratorVisitorTrait: DataRegeneratorVisitorTrait, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int = 0, joinRightTableFieldIndex: Int = 0): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val reqData = dataRegeneratorVisitorTrait.visit(this, existingData,joinLeftTableFieldIndex, joinRightTableFieldIndex)
    val results = this.child.acceptRegeneration(dataRegeneratorVisitorTrait, reqData,joinLeftTableFieldIndex, joinRightTableFieldIndex)
    results
  }
}

/**
 * Applies a [[Generator]] to a stream of input rows, combining the
 * output of each into a new stream of rows.  This operation is similar to a `flatMap` in functional
 * programming with one important additional feature, which allows the input rows to be joined with
 * their output.
 *
 * @param generator            the generator expression
 * @param unrequiredChildIndex this parameter starts as Nil and gets filled by the Optimizer.
 *                             It's used as an optimization for omitting data generation that will
 *                             be discarded next by a projection.
 *                             A common use case is when we explode(array(..)) and are interested
 *                             only in the exploded data and not in the original array. before this
 *                             optimization the array got duplicated for each of its elements,
 *                             causing O(n^^2) memory consumption. (see [SPARK-21657])
 * @param outer                when true, each input row will be output at least once, even if the output of the
 *                             given `generator` is empty.
 * @param qualifier            Qualifier for the attributes of generator(UDTF)
 * @param generatorOutput      The output schema of the Generator.
 * @param child                Children logical plan node
 */
case class Generate(
                     generator: Generator,
                     unrequiredChildIndex: Seq[Int],
                     outer: Boolean,
                     qualifier: Option[String],
                     generatorOutput: Seq[Attribute],
                     child: LogicalPlan)
  extends UnaryNode {

  lazy val requiredChildOutput: Seq[Attribute] = {
    val unrequiredSet = unrequiredChildIndex.toSet
    child.output.zipWithIndex.filterNot(t => unrequiredSet.contains(t._2)).map(_._1)
  }

  override lazy val resolved: Boolean = {
    generator.resolved &&
      childrenResolved &&
      generator.elementSchema.length == generatorOutput.length &&
      generatorOutput.forall(_.resolved)
  }

  override def producedAttributes: AttributeSet = AttributeSet(generatorOutput)

  def qualifiedGeneratorOutput: Seq[Attribute] = {
    val qualifiedOutput = qualifier.map { q =>
      // prepend the new qualifier to the existed one
      generatorOutput.map(a => a.withQualifier(Seq(q)))
    }.getOrElse(generatorOutput)
    val nullableOutput = qualifiedOutput.map {
      // if outer, make all attributes nullable, otherwise keep existing nullability
      a => a.withNullability(outer || a.nullable)
    }
    nullableOutput
  }

  def output: Seq[Attribute] = requiredChildOutput ++ qualifiedGeneratorOutput
}

case class Filter(condition: Expression, child: LogicalPlan)
  extends OrderPreservingUnaryNode with PredicateHelper with SubQueryExist with EnableRegeneration {
  override def output: Seq[Attribute] = child.output

  override def maxRows: Option[Long] = child.maxRows

  override protected lazy val validConstraints: Set[Expression] = {
    val predicates = splitConjunctivePredicates(condition)
      .filterNot(SubqueryExpression.hasCorrelatedSubquery)
    child.constraints.union(predicates.toSet)
  }

  override def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    if (this.child.isInstanceOf[Join]) {
      var initialSubquery = new SubQueryStorage(subQueryList(0).subQueryString, subQueryList(0).subQueryIndex)
      subQueryList.remove(0)
      var filterList = this.condition.accept(subQueryVisitor, subQueryList)
      filterList.prepend(initialSubquery)
      var remainingQueries = this.child.accept(subQueryVisitor, filterList)
      subQueryList.appendAll(remainingQueries)
      subQueryList
    } else {
      this.child.accept(subQueryVisitor, subQueryList)
      var filterList = this.condition.accept(subQueryVisitor, subQueryList)
      subQueryVisitor.visit(filterList, subQueryList)
    }
    //this.condition.toString()
  }

  override def acceptRegeneration(dataRegeneratorVisitorTrait: DataRegeneratorVisitorTrait, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int = 0, joinRightTableFieldIndex: Int = 0): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    if(this.child.isInstanceOf[Join]){
      return this.child.acceptRegeneration(dataRegeneratorVisitorTrait, existingData,joinLeftTableFieldIndex, joinRightTableFieldIndex)
    }
    return this.condition.acceptRegeneration(dataRegeneratorVisitorTrait, existingData,joinLeftTableFieldIndex, joinRightTableFieldIndex)
  }
}

abstract class SetOperation(left: LogicalPlan, right: LogicalPlan) extends BinaryNode {

  def duplicateResolved: Boolean = left.outputSet.intersect(right.outputSet).isEmpty

  protected def leftConstraints: Set[Expression] = left.constraints

  protected def rightConstraints: Set[Expression] = {
    require(left.output.size == right.output.size)
    val attributeRewrites = AttributeMap(right.output.zip(left.output))
    right.constraints.map(_ transform {
      case a: Attribute => attributeRewrites(a)
    })
  }

  override lazy val resolved: Boolean =
    childrenResolved &&
      left.output.length == right.output.length &&
      left.output.zip(right.output).forall { case (l, r) =>
        l.dataType.sameType(r.dataType)
      } && duplicateResolved
}

object SetOperation {
  def unapply(p: SetOperation): Option[(LogicalPlan, LogicalPlan)] = Some((p.left, p.right))
}

case class Intersect(
                      left: LogicalPlan,
                      right: LogicalPlan,
                      isAll: Boolean) extends SetOperation(left, right) {

  override def nodeName: String = getClass.getSimpleName + (if (isAll) "All" else "")

  override def output: Seq[Attribute] =
    left.output.zip(right.output).map { case (leftAttr, rightAttr) =>
      leftAttr.withNullability(leftAttr.nullable && rightAttr.nullable)
    }

  override protected lazy val validConstraints: Set[Expression] =
    leftConstraints.union(rightConstraints)

  override def maxRows: Option[Long] = {
    if (children.exists(_.maxRows.isEmpty)) {
      None
    } else {
      Some(children.flatMap(_.maxRows).min)
    }
  }
}

case class Except(
                   left: LogicalPlan,
                   right: LogicalPlan,
                   isAll: Boolean) extends SetOperation(left, right) {
  override def nodeName: String = getClass.getSimpleName + (if (isAll) "All" else "")

  /** We don't use right.output because those rows get excluded from the set. */
  override def output: Seq[Attribute] = left.output

  override protected lazy val validConstraints: Set[Expression] = leftConstraints
}

/** Factory for constructing new `Union` nodes. */
object Union {
  def apply(left: LogicalPlan, right: LogicalPlan): Union = {
    Union(left :: right :: Nil)
  }
}

/**
 * Logical plan for unioning two plans, without a distinct. This is UNION ALL in SQL.
 */
case class Union(children: Seq[LogicalPlan]) extends LogicalPlan {
  override def maxRows: Option[Long] = {
    if (children.exists(_.maxRows.isEmpty)) {
      None
    } else {
      Some(children.flatMap(_.maxRows).sum)
    }
  }

  /**
   * Note the definition has assumption about how union is implemented physically.
   */
  override def maxRowsPerPartition: Option[Long] = {
    if (children.exists(_.maxRowsPerPartition.isEmpty)) {
      None
    } else {
      Some(children.flatMap(_.maxRowsPerPartition).sum)
    }
  }

  def duplicateResolved: Boolean = {
    children.map(_.outputSet.size).sum ==
      AttributeSet.fromAttributeSets(children.map(_.outputSet)).size
  }

  // updating nullability to make all the children consistent
  override def output: Seq[Attribute] = {
    children.map(_.output).transpose.map { attrs =>
      val firstAttr = attrs.head
      val nullable = attrs.exists(_.nullable)
      val newDt = attrs.map(_.dataType).reduce(StructType.merge)
      if (firstAttr.dataType == newDt) {
        firstAttr.withNullability(nullable)
      } else {
        AttributeReference(firstAttr.name, newDt, nullable, firstAttr.metadata)(
          firstAttr.exprId, firstAttr.qualifier)
      }
    }
  }

  override lazy val resolved: Boolean = {
    // allChildrenCompatible needs to be evaluated after childrenResolved
    def allChildrenCompatible: Boolean =
      children.tail.forall(child =>
        // compare the attribute number with the first child
        child.output.length == children.head.output.length &&
          // compare the data types with the first child
          child.output.zip(children.head.output).forall {
            case (l, r) => l.dataType.sameType(r.dataType)
          })

    children.length > 1 && childrenResolved && allChildrenCompatible
  }

  /**
   * Maps the constraints containing a given (original) sequence of attributes to those with a
   * given (reference) sequence of attributes. Given the nature of union, we expect that the
   * mapping between the original and reference sequences are symmetric.
   */
  private def rewriteConstraints(
                                  reference: Seq[Attribute],
                                  original: Seq[Attribute],
                                  constraints: Set[Expression]): Set[Expression] = {
    require(reference.size == original.size)
    val attributeRewrites = AttributeMap(original.zip(reference))
    constraints.map(_ transform {
      case a: Attribute => attributeRewrites(a)
    })
  }

  private def merge(a: Set[Expression], b: Set[Expression]): Set[Expression] = {
    val common = a.intersect(b)
    // The constraint with only one reference could be easily inferred as predicate
    // Grouping the constraints by it's references so we can combine the constraints with same
    // reference together
    val othera = a.diff(common).filter(_.references.size == 1).groupBy(_.references.head)
    val otherb = b.diff(common).filter(_.references.size == 1).groupBy(_.references.head)
    // loose the constraints by: A1 && B1 || A2 && B2  ->  (A1 || A2) && (B1 || B2)
    val others = (othera.keySet intersect otherb.keySet).map { attr =>
      Or(othera(attr).reduceLeft(And), otherb(attr).reduceLeft(And))
    }
    common ++ others
  }

  override protected lazy val validConstraints: Set[Expression] = {
    children
      .map(child => rewriteConstraints(children.head.output, child.output, child.constraints))
      .reduce(merge(_, _))
  }
}

case class Join(
                 left: LogicalPlan,
                 right: LogicalPlan,
                 joinType: JoinType,
                 condition: Option[Expression],
                 hint: JoinHint)
  extends BinaryNode with PredicateHelper with SubQueryExist with EnableRegeneration {

  override def output: Seq[Attribute] = {
    joinType match {
      case j: ExistenceJoin =>
        left.output :+ j.exists
      case LeftExistence(_) =>
        left.output
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case FullOuter =>
        left.output.map(_.withNullability(true)) ++ right.output.map(_.withNullability(true))
      case _ =>
        left.output ++ right.output
    }
  }

  override lazy val validConstraints: Set[Expression] = {
    joinType match {
      case _: InnerLike if condition.isDefined =>
        left.constraints
          .union(right.constraints)
          .union(splitConjunctivePredicates(condition.get).toSet)
      case LeftSemi if condition.isDefined =>
        left.constraints
          .union(splitConjunctivePredicates(condition.get).toSet)
      case j: ExistenceJoin =>
        left.constraints
      case _: InnerLike =>
        left.constraints.union(right.constraints)
      case LeftExistence(_) =>
        left.constraints
      case LeftOuter =>
        left.constraints
      case RightOuter =>
        right.constraints
      case FullOuter =>
        Set.empty[Expression]
    }
  }

  def duplicateResolved: Boolean = left.outputSet.intersect(right.outputSet).isEmpty

  // Joins are only resolved if they don't introduce ambiguous expression ids.
  // NaturalJoin should be ready for resolution only if everything else is resolved here
  lazy val resolvedExceptNatural: Boolean = {
    childrenResolved &&
      expressions.forall(_.resolved) &&
      duplicateResolved &&
      condition.forall(_.dataType == BooleanType)
  }

  // if not a natural join, use `resolvedExceptNatural`. if it is a natural join or
  // using join, we still need to eliminate natural or using before we mark it resolved.
  override lazy val resolved: Boolean = joinType match {
    case NaturalJoin(_) => false
    case UsingJoin(_, _) => false
    case _ => resolvedExceptNatural
  }

  // Ignore hint for canonicalization
  protected override def doCanonicalize(): LogicalPlan =
    super.doCanonicalize().asInstanceOf[Join].copy(hint = JoinHint.NONE)

  // Do not include an empty join hint in string description
  protected override def stringArgs: Iterator[Any] = super.stringArgs.filter { e =>
    (!e.isInstanceOf[JoinHint]
      || e.asInstanceOf[JoinHint].leftHint.isDefined
      || e.asInstanceOf[JoinHint].rightHint.isDefined)
  }

  override def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    subQueryVisitor.visit(this, subQueryList)
    //this.child.accept(subQueryVisitor,subQueryList)
  }

  override def acceptRegeneration(dataRegeneratorVisitorTrait: DataRegeneratorVisitorTrait, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int = 0, joinRightTableFieldIndex: Int = 0): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    dataRegeneratorVisitorTrait.visit(this, existingData,joinLeftTableFieldIndex,joinRightTableFieldIndex)
  }

}

/**
 * Insert query result into a directory.
 *
 * @param isLocal   Indicates whether the specified directory is local directory
 * @param storage   Info about output file, row and what serialization format
 * @param provider  Specifies what data source to use; only used for data source file.
 * @param child     The query to be executed
 * @param overwrite If true, the existing directory will be overwritten
 *
 *                  Note that this plan is unresolved and has to be replaced by the concrete implementations
 *                  during analysis.
 */
case class InsertIntoDir(
                          isLocal: Boolean,
                          storage: CatalogStorageFormat,
                          provider: Option[String],
                          child: LogicalPlan,
                          overwrite: Boolean = true)
  extends UnaryNode {

  override def output: Seq[Attribute] = Seq.empty

  override lazy val resolved: Boolean = false
}

/**
 * A container for holding the view description(CatalogTable), and the output of the view. The
 * child should be a logical plan parsed from the `CatalogTable.viewText`, should throw an error
 * if the `viewText` is not defined.
 * This operator will be removed at the end of analysis stage.
 *
 * @param desc   A view description(CatalogTable) that provides necessary information to resolve the
 *               view.
 * @param output The output of a view operator, this is generated during planning the view, so that
 *               we are able to decouple the output from the underlying structure.
 * @param child  The logical plan of a view operator, it should be a logical plan parsed from the
 *               `CatalogTable.viewText`, should throw an error if the `viewText` is not defined.
 */
case class View(
                 desc: CatalogTable,
                 output: Seq[Attribute],
                 child: LogicalPlan) extends LogicalPlan with MultiInstanceRelation {

  override def producedAttributes: AttributeSet = outputSet

  override lazy val resolved: Boolean = child.resolved

  override def children: Seq[LogicalPlan] = child :: Nil

  override def newInstance(): LogicalPlan = copy(output = output.map(_.newInstance()))

  override def simpleString(maxFields: Int): String = {
    s"View (${desc.identifier}, ${output.mkString("[", ",", "]")})"
  }
}

/**
 * A container for holding named common table expressions (CTEs) and a query plan.
 * This operator will be removed during analysis and the relations will be substituted into child.
 *
 * @param child        The final query of this CTE.
 * @param cteRelations A sequence of pair (alias, the CTE definition) that this CTE defined
 *                     Each CTE can see the base tables and the previously defined CTEs only.
 */
case class With(child: LogicalPlan, cteRelations: Seq[(String, SubqueryAlias)]) extends UnaryNode {
  override def output: Seq[Attribute] = child.output

  override def simpleString(maxFields: Int): String = {
    val cteAliases = truncatedString(cteRelations.map(_._1), "[", ", ", "]", maxFields)
    s"CTE $cteAliases"
  }

  override def innerChildren: Seq[LogicalPlan] = cteRelations.map(_._2)
}

case class WithWindowDefinition(
                                 windowDefinitions: Map[String, WindowSpecDefinition],
                                 child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
}

/**
 * @param order  The ordering expressions
 * @param global True means global sorting apply for entire data set,
 *               False means sorting only apply within the partition.
 * @param child  Child logical plan
 */
case class Sort(
                 order: Seq[SortOrder],
                 global: Boolean,
                 child: LogicalPlan) extends UnaryNode with SubQueryExist {
  override def output: Seq[Attribute] = child.output

  override def maxRows: Option[Long] = child.maxRows

  override def outputOrdering: Seq[SortOrder] = order

  override def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    this.child.accept(subQueryVisitor, subQueryList)
    subQueryVisitor.visit(this, subQueryList)
  }
}

/** Factory for constructing new `Range` nodes. */
object Range {
  def apply(start: Long, end: Long, step: Long,
            numSlices: Option[Int], isStreaming: Boolean = false): Range = {
    val output = StructType(StructField("id", LongType, nullable = false) :: Nil).toAttributes
    new Range(start, end, step, numSlices, output, isStreaming)
  }

  def apply(start: Long, end: Long, step: Long, numSlices: Int): Range = {
    Range(start, end, step, Some(numSlices))
  }
}

case class Range(
                  start: Long,
                  end: Long,
                  step: Long,
                  numSlices: Option[Int],
                  output: Seq[Attribute],
                  override val isStreaming: Boolean)
  extends LeafNode with MultiInstanceRelation {

  require(step != 0, s"step ($step) cannot be 0")

  val numElements: BigInt = {
    val safeStart = BigInt(start)
    val safeEnd = BigInt(end)
    if ((safeEnd - safeStart) % step == 0 || (safeEnd > safeStart) != (step > 0)) {
      (safeEnd - safeStart) / step
    } else {
      // the remainder has the same sign with range, could add 1 more
      (safeEnd - safeStart) / step + 1
    }
  }

  def toSQL(): String = {
    if (numSlices.isDefined) {
      s"SELECT id AS `${output.head.name}` FROM range($start, $end, $step, ${numSlices.get})"
    } else {
      s"SELECT id AS `${output.head.name}` FROM range($start, $end, $step)"
    }
  }

  override def newInstance(): Range = copy(output = output.map(_.newInstance()))

  override def simpleString(maxFields: Int): String = {
    s"Range ($start, $end, step=$step, splits=$numSlices)"
  }

  override def computeStats(): Statistics = {
    Statistics(sizeInBytes = LongType.defaultSize * numElements)
  }

  override def outputOrdering: Seq[SortOrder] = {
    val order = if (step > 0) {
      Ascending
    } else {
      Descending
    }
    output.map(a => SortOrder(a, order))
  }
}

/**
 * This is a Group by operator with the aggregate functions and projections.
 *
 * @param groupingExpressions  expressions for grouping keys
 * @param aggregateExpressions expressions for a project list, which could contain
 *                             [[AggregateFunction]]s.
 *
 *                             Note: Currently, aggregateExpressions is the project list of this Group by operator. Before
 *                             separating projection from grouping and aggregate, we should avoid expression-level optimization
 *                             on aggregateExpressions, which could reference an expression in groupingExpressions.
 *                             For example, see the rule [[org.apache.spark.sql.catalyst.optimizer.SimplifyExtractValueOps]]
 */
case class Aggregate(
                      groupingExpressions: Seq[Expression],
                      aggregateExpressions: Seq[NamedExpression],
                      child: LogicalPlan)
  extends UnaryNode with SubQueryExist with EnableRegeneration {

  override lazy val resolved: Boolean = {
    val hasWindowExpressions = aggregateExpressions.exists(_.collect {
      case window: WindowExpression => window
    }.nonEmpty
    )

    !expressions.exists(!_.resolved) && childrenResolved && !hasWindowExpressions
  }

  override def output: Seq[Attribute] = aggregateExpressions.map(_.toAttribute)

  override def maxRows: Option[Long] = child.maxRows

  override lazy val validConstraints: Set[Expression] = {
    val nonAgg = aggregateExpressions.filter(_.find(_.isInstanceOf[AggregateExpression]).isEmpty)
    getAllValidConstraints(nonAgg)
  }

  override def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    /*if(this.child.isInstanceOf[Filter]){
      subQueryList.append(new SubQueryStorage("Select " + this.schema.fields.toString + " ",0))
      this.child.accept(subQueryVisitor,subQueryList)
    }*/
    subQueryVisitor.visit(this, subQueryList)
    //this.child.accept(subQueryVisitor,subQueryList)
  }

  override def acceptRegeneration(dataRegeneratorVisitorTrait: DataRegeneratorVisitorTrait, existingData: RDD[(GenericRowWithSchema, String, Int, String, String)], joinLeftTableFieldIndex: Int = 0, joinRightTableFieldIndex: Int = 0): RDD[(GenericRowWithSchema, String, Int, String, String)] = {
    val nonAggregateData = existingData.filter(x => x._3 != 111111)
    val aggregateData = existingData.filter(x => x._3 == 111111)
    val results = dataRegeneratorVisitorTrait.visit(this, aggregateData,joinLeftTableFieldIndex, joinRightTableFieldIndex)
    results.union(nonAggregateData)
  }
}

case class Window(
                   windowExpressions: Seq[NamedExpression],
                   partitionSpec: Seq[Expression],
                   orderSpec: Seq[SortOrder],
                   child: LogicalPlan) extends UnaryNode {

  override def output: Seq[Attribute] =
    child.output ++ windowExpressions.map(_.toAttribute)

  def windowOutputSet: AttributeSet = AttributeSet(windowExpressions.map(_.toAttribute))
}

object Expand {
  /**
   * Build bit mask from attributes of selected grouping set. A bit in the bitmask is corresponding
   * to an attribute in group by attributes sequence, the selected attribute has corresponding bit
   * set to 0 and otherwise set to 1. For example, if we have GroupBy attributes (a, b, c, d), the
   * bitmask 5(whose binary form is 0101) represents grouping set (a, c).
   *
   * @param groupingSetAttrs The attributes of selected grouping set
   * @param attrMap          Mapping group by attributes to its index in attributes sequence
   * @return The bitmask which represents the selected attributes out of group by attributes.
   */
  private def buildBitmask(
                            groupingSetAttrs: Seq[Attribute],
                            attrMap: Map[Attribute, Int]): Int = {
    val numAttributes = attrMap.size
    val mask = (1 << numAttributes) - 1
    // Calculate the attrbute masks of selected grouping set. For example, if we have GroupBy
    // attributes (a, b, c, d), grouping set (a, c) will produce the following sequence:
    // (15, 7, 13), whose binary form is (1111, 0111, 1101)
    val masks = (mask +: groupingSetAttrs.map(attrMap).map(index =>
      // 0 means that the column at the given index is a grouping column, 1 means it is not,
      // so we unset the bit in bitmap.
      ~(1 << (numAttributes - 1 - index))
    ))
    // Reduce masks to generate an bitmask for the selected grouping set.
    masks.reduce(_ & _)
  }

  /**
   * Apply the all of the GroupExpressions to every input row, hence we will get
   * multiple output rows for an input row.
   *
   * @param groupingSetsAttrs The attributes of grouping sets
   * @param groupByAliases    The aliased original group by expressions
   * @param groupByAttrs      The attributes of aliased group by expressions
   * @param gid               Attribute of the grouping id
   * @param child             Child operator
   */
  def apply(
             groupingSetsAttrs: Seq[Seq[Attribute]],
             groupByAliases: Seq[Alias],
             groupByAttrs: Seq[Attribute],
             gid: Attribute,
             child: LogicalPlan): Expand = {
    val attrMap = groupByAttrs.zipWithIndex.toMap

    val hasDuplicateGroupingSets = groupingSetsAttrs.size !=
      groupingSetsAttrs.map(_.map(_.exprId).toSet).distinct.size

    // Create an array of Projections for the child projection, and replace the projections'
    // expressions which equal GroupBy expressions with Literal(null), if those expressions
    // are not set for this grouping set.
    val projections = groupingSetsAttrs.zipWithIndex.map { case (groupingSetAttrs, i) =>
      val projAttrs = child.output ++ groupByAttrs.map { attr =>
        if (!groupingSetAttrs.contains(attr)) {
          // if the input attribute in the Invalid Grouping Expression set of for this group
          // replace it with constant null
          Literal.create(null, attr.dataType)
        } else {
          attr
        }
        // groupingId is the last output, here we use the bit mask as the concrete value for it.
      } :+ Literal.create(buildBitmask(groupingSetAttrs, attrMap), IntegerType)

      if (hasDuplicateGroupingSets) {
        // If `groupingSetsAttrs` has duplicate entries (e.g., GROUPING SETS ((key), (key))),
        // we add one more virtual grouping attribute (`_gen_grouping_pos`) to avoid
        // wrongly grouping rows with the same grouping ID.
        projAttrs :+ Literal.create(i, IntegerType)
      } else {
        projAttrs
      }
    }

    // the `groupByAttrs` has different meaning in `Expand.output`, it could be the original
    // grouping expression or null, so here we create new instance of it.
    val output = if (hasDuplicateGroupingSets) {
      val gpos = AttributeReference("_gen_grouping_pos", IntegerType, false)()
      child.output ++ groupByAttrs.map(_.newInstance) :+ gid :+ gpos
    } else {
      child.output ++ groupByAttrs.map(_.newInstance) :+ gid
    }
    Expand(projections, output, Project(child.output ++ groupByAliases, child))
  }
}

/**
 * Apply a number of projections to every input row, hence we will get multiple output rows for
 * an input row.
 *
 * @param projections to apply
 * @param output      of all projections.
 * @param child       operator.
 */
case class Expand(
                   projections: Seq[Seq[Expression]],
                   output: Seq[Attribute],
                   child: LogicalPlan) extends UnaryNode {
  @transient
  override lazy val references: AttributeSet =
    AttributeSet(projections.flatten.flatMap(_.references))

  override def producedAttributes: AttributeSet = AttributeSet(output diff child.output)

  // This operator can reuse attributes (for example making them null when doing a roll up) so
  // the constraints of the child may no longer be valid.
  override protected lazy val validConstraints: Set[Expression] = Set.empty[Expression]
}

/**
 * A GROUP BY clause with GROUPING SETS can generate a result set equivalent
 * to generated by a UNION ALL of multiple simple GROUP BY clauses.
 *
 * We will transform GROUPING SETS into logical plan Aggregate(.., Expand) in Analyzer
 *
 * @param selectedGroupByExprs A sequence of selected GroupBy expressions, all exprs should
 *                             exist in groupByExprs.
 * @param groupByExprs         The Group By expressions candidates.
 * @param child                Child operator
 * @param aggregations         The Aggregation expressions, those non selected group by expressions
 *                             will be considered as constant null if it appears in the expressions
 */
case class GroupingSets(
                         selectedGroupByExprs: Seq[Seq[Expression]],
                         groupByExprs: Seq[Expression],
                         child: LogicalPlan,
                         aggregations: Seq[NamedExpression]) extends UnaryNode {

  override def output: Seq[Attribute] = aggregations.map(_.toAttribute)

  // Needs to be unresolved before its translated to Aggregate + Expand because output attributes
  // will change in analysis.
  override lazy val resolved: Boolean = false
}

/**
 * A constructor for creating a pivot, which will later be converted to a [[Project]]
 * or an [[Aggregate]] during the query analysis.
 *
 * @param groupByExprsOpt A sequence of group by expressions. This field should be None if coming
 *                        from SQL, in which group by expressions are not explicitly specified.
 * @param pivotColumn     The pivot column.
 * @param pivotValues     A sequence of values for the pivot column.
 * @param aggregates      The aggregation expressions, each with or without an alias.
 * @param child           Child operator
 */
case class Pivot(
                  groupByExprsOpt: Option[Seq[NamedExpression]],
                  pivotColumn: Expression,
                  pivotValues: Seq[Expression],
                  aggregates: Seq[Expression],
                  child: LogicalPlan) extends UnaryNode {
  override lazy val resolved = false // Pivot will be replaced after being resolved.

  override def output: Seq[Attribute] = {
    val pivotAgg = aggregates match {
      case agg :: Nil =>
        pivotValues.map(value => AttributeReference(value.toString, agg.dataType)())
      case _ =>
        pivotValues.flatMap { value =>
          aggregates.map(agg => AttributeReference(value + "_" + agg.sql, agg.dataType)())
        }
    }
    groupByExprsOpt.getOrElse(Seq.empty).map(_.toAttribute) ++ pivotAgg
  }
}

/**
 * A constructor for creating a logical limit, which is split into two separate logical nodes:
 * a [[LocalLimit]], which is a partition local limit, followed by a [[GlobalLimit]].
 *
 * This muds the water for clean logical/physical separation, and is done for better limit pushdown.
 * In distributed query processing, a non-terminal global limit is actually an expensive operation
 * because it requires coordination (in Spark this is done using a shuffle).
 *
 * In most cases when we want to push down limit, it is often better to only push some partition
 * local limit. Consider the following:
 *
 * GlobalLimit(Union(A, B))
 *
 * It is better to do
 * GlobalLimit(Union(LocalLimit(A), LocalLimit(B)))
 *
 * than
 * Union(GlobalLimit(A), GlobalLimit(B)).
 *
 * So we introduced LocalLimit and GlobalLimit in the logical plan node for limit pushdown.
 */
object Limit {
  def apply(limitExpr: Expression, child: LogicalPlan): UnaryNode = {
    GlobalLimit(limitExpr, LocalLimit(limitExpr, child))
  }

  def unapply(p: GlobalLimit): Option[(Expression, LogicalPlan)] = {
    p match {
      case GlobalLimit(le1, LocalLimit(le2, child)) if le1 == le2 => Some((le1, child))
      case _ => None
    }
  }
}

/**
 * A global (coordinated) limit. This operator can emit at most `limitExpr` number in total.
 *
 * See [[Limit]] for more information.
 */
case class GlobalLimit(limitExpr: Expression, child: LogicalPlan) extends OrderPreservingUnaryNode with SubQueryExist {
  override def output: Seq[Attribute] = child.output

  override def maxRows: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }

  override def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    this.child.accept(subQueryVisitor, subQueryList)
    subQueryVisitor.visit(this, subQueryList)
  }
}

/**
 * A partition-local (non-coordinated) limit. This operator can emit at most `limitExpr` number
 * of tuples on each physical partition.
 *
 * See [[Limit]] for more information.
 */
case class LocalLimit(limitExpr: Expression, child: LogicalPlan) extends OrderPreservingUnaryNode with SubQueryExist {
  override def output: Seq[Attribute] = child.output

  override def maxRowsPerPartition: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }

  override def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    this.child.accept(subQueryVisitor, subQueryList)
  }
}

/**
 * This is similar with [[Limit]] except:
 *
 * - It does not have plans for global/local separately because currently there is only single
 * implementation which initially mimics both global/local tails. See
 * `org.apache.spark.sql.execution.CollectTailExec` and
 * `org.apache.spark.sql.execution.CollectLimitExec`
 *
 * - Currently, this plan can only be a root node.
 */
case class Tail(limitExpr: Expression, child: LogicalPlan) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output

  override def maxRows: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }
}

/**
 * Aliased subquery.
 *
 * @param identifier the alias identifier for this subquery.
 * @param child      the logical plan of this subquery.
 */
case class SubqueryAlias(
                          identifier: AliasIdentifier,
                          child: LogicalPlan)
  extends OrderPreservingUnaryNode with SubQueryExist {

  def alias: String = identifier.name

  override def output: Seq[Attribute] = {
    val qualifierList = identifier.qualifier :+ alias
    child.output.map(_.withQualifier(qualifierList))
  }

  override def doCanonicalize(): LogicalPlan = child.canonicalized

  override def accept(subQueryVisitor: SubQueryVisitor, subQueryList: ArrayBuffer[SubQueryStorage]): ArrayBuffer[SubQueryStorage] = {
    subQueryVisitor.visit(this, subQueryList)
  }
}

object SubqueryAlias {
  def apply(
             identifier: String,
             child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(identifier), child)
  }

  def apply(
             identifier: String,
             database: String,
             child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(identifier, Seq(database)), child)
  }

  def apply(
             multipartIdentifier: Seq[String],
             child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(multipartIdentifier.last, multipartIdentifier.init), child)
  }
}

/**
 * Sample the dataset.
 *
 * @param lowerBound      Lower-bound of the sampling probability (usually 0.0)
 * @param upperBound      Upper-bound of the sampling probability. The expected fraction sampled
 *                        will be ub - lb.
 * @param withReplacement Whether to sample with replacement.
 * @param seed            the random seed
 * @param child           the LogicalPlan
 */
case class Sample(
                   lowerBound: Double,
                   upperBound: Double,
                   withReplacement: Boolean,
                   seed: Long,
                   child: LogicalPlan) extends UnaryNode {

  val eps = RandomSampler.roundingEpsilon
  val fraction = upperBound - lowerBound
  if (withReplacement) {
    require(
      fraction >= 0.0 - eps,
      s"Sampling fraction ($fraction) must be nonnegative with replacement")
  } else {
    require(
      fraction >= 0.0 - eps && fraction <= 1.0 + eps,
      s"Sampling fraction ($fraction) must be on interval [0, 1] without replacement")
  }

  override def output: Seq[Attribute] = child.output
}

/**
 * Returns a new logical plan that dedups input rows.
 */
case class Distinct(child: LogicalPlan) extends UnaryNode {
  override def maxRows: Option[Long] = child.maxRows

  override def output: Seq[Attribute] = child.output
}

/**
 * A base interface for [[RepartitionByExpression]] and [[Repartition]]
 */
abstract class RepartitionOperation extends UnaryNode {
  def shuffle: Boolean

  def numPartitions: Int

  override def output: Seq[Attribute] = child.output
}

/**
 * Returns a new RDD that has exactly `numPartitions` partitions. Differs from
 * [[RepartitionByExpression]] as this method is called directly by DataFrame's, because the user
 * asked for `coalesce` or `repartition`. [[RepartitionByExpression]] is used when the consumer
 * of the output requires some specific ordering or distribution of the data.
 */
case class Repartition(numPartitions: Int, shuffle: Boolean, child: LogicalPlan)
  extends RepartitionOperation {
  require(numPartitions > 0, s"Number of partitions ($numPartitions) must be positive.")
}

/**
 * This method repartitions data using [[Expression]]s into `numPartitions`, and receives
 * information about the number of partitions during execution. Used when a specific ordering or
 * distribution is expected by the consumer of the query result. Use [[Repartition]] for RDD-like
 * `coalesce` and `repartition`.
 */
case class RepartitionByExpression(
                                    partitionExpressions: Seq[Expression],
                                    child: LogicalPlan,
                                    numPartitions: Int) extends RepartitionOperation {

  require(numPartitions > 0, s"Number of partitions ($numPartitions) must be positive.")

  val partitioning: Partitioning = {
    val (sortOrder, nonSortOrder) = partitionExpressions.partition(_.isInstanceOf[SortOrder])

    require(sortOrder.isEmpty || nonSortOrder.isEmpty,
      s"${getClass.getSimpleName} expects that either all its `partitionExpressions` are of type " +
        "`SortOrder`, which means `RangePartitioning`, or none of them are `SortOrder`, which " +
        "means `HashPartitioning`. In this case we have:" +
        s"""
           |SortOrder: $sortOrder
           |NonSortOrder: $nonSortOrder
       """.stripMargin)

    if (sortOrder.nonEmpty) {
      RangePartitioning(sortOrder.map(_.asInstanceOf[SortOrder]), numPartitions)
    } else if (nonSortOrder.nonEmpty) {
      HashPartitioning(nonSortOrder, numPartitions)
    } else {
      RoundRobinPartitioning(numPartitions)
    }
  }

  override def maxRows: Option[Long] = child.maxRows

  override def shuffle: Boolean = true
}

/**
 * A relation with one row. This is used in "SELECT ..." without a from clause.
 */
case class OneRowRelation() extends LeafNode {
  override def maxRows: Option[Long] = Some(1)

  override def output: Seq[Attribute] = Nil

  override def computeStats(): Statistics = Statistics(sizeInBytes = 1)

  /** [[org.apache.spark.sql.catalyst.trees.TreeNode.makeCopy()]] does not support 0-arg ctor. */
  override def makeCopy(newArgs: Array[AnyRef]): OneRowRelation = {
    val newCopy = OneRowRelation()
    newCopy.copyTagsFrom(this)
    newCopy
  }
}

/** A logical plan for `dropDuplicates`. */
case class Deduplicate(
                        keys: Seq[Attribute],
                        child: LogicalPlan) extends UnaryNode {

  override def output: Seq[Attribute] = child.output
}

/**
 * A trait to represent the commands that support subqueries.
 * This is used to whitelist such commands in the subquery-related checks.
 */
trait SupportsSubquery extends LogicalPlan

/**
 * Collect arbitrary (named) metrics from a dataset. As soon as the query reaches a completion
 * point (batch query completes or streaming query epoch completes) an event is emitted on the
 * driver which can be observed by attaching a listener to the spark session. The metrics are named
 * so we can collect metrics at multiple places in a single dataset.
 *
 * This node behaves like a global aggregate. All the metrics collected must be aggregate functions
 * or be literals.
 */
case class CollectMetrics(
                           name: String,
                           metrics: Seq[NamedExpression],
                           child: LogicalPlan)
  extends UnaryNode {

  override lazy val resolved: Boolean = {
    name.nonEmpty && metrics.nonEmpty && metrics.forall(_.resolved) && childrenResolved
  }

  override def output: Seq[Attribute] = child.output
}