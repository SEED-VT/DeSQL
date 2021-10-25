
import org.apache.spark.sdb._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.plans.logical.SubQueryGeneratorVisitor
import org.apache.spark.sql.execution.joins.BroadcastHashJoinExec
import org.apache.spark.sql.execution.{InputAdapter, ProjectExec, SparkPlan}
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeRowWriter
import org.apache.spark.util.AccumulatorV2
import org.apache.spark.rdd.JdbcRDD
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, UnsafeRow}

import java.sql.{Connection, DriverManager, ResultSet, SQLException, Statement}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


case class Person(age: Long, name: String)

object SQLTest {

  val spark = SparkSession
    .builder()
    .appName("Spark SQL basic example")
    .config("spark.some.config.option", "some-value")
    .master("local[*]")
    .getOrCreate()
  val t1 = System.nanoTime()
  val tapsAcc = new TapsAccumulatorV2
  spark.sparkContext.register(tapsAcc, "TapsAcc1")
  val subQueryAcc = new SubQueryAccumulatorV2
  spark.sparkContext.register(subQueryAcc, "SubQueryAcc1")
  case class ResultClass(var query: String, results: ArrayBuffer[String])
  def main(args: Array[String]): Unit = {
    case class Person(name: String, age: Int)

    // Create an RDD of Person objects and register it as a table.
    val people = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/people.json")
    val peopleCopy = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/peopleCopy.json")
    val employees = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/employees.json")
    val employeesCopy = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/employeesCopy.json")
    val aircrafts_data = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/aircrafts_data.json")
    val airports_data = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/airports_data.json")
    val boarding_passes = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/boarding_passes.json")
    val bookings = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/bookings.json")
    val flights = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/flights.json")
    val seats = spark.read.json("/home/sabaat/IdeaProjects/spark-sql-debug/examples/src/main/resources/seats.json")
    airports_data.createOrReplaceTempView("airports_data")
    bookings.createOrReplaceTempView("bookings")
    boarding_passes.createOrReplaceTempView("boarding_passes")
    flights.createOrReplaceTempView("flights")
    aircrafts_data.createOrReplaceTempView("aircrafts_data")
    seats.createOrReplaceTempView("seats")
    people.createOrReplaceTempView("people")
    peopleCopy.createOrReplaceTempView("peopleCopy")
    employees.createOrReplaceTempView("employees")
    employeesCopy.createOrReplaceTempView("employeesCopy")

    //val teenagers = spark.sql("select * from people where people.name IN (select count(age) from people group by name)");
    //val teenagers = spark.sql("select * from people where people.age > 12");
    //val teenagers = spark.sql("select * from people where people.age > 12 AND people.age < 31")
    //val teenagers = spark.sql("select * from people where people.age < 13 OR people.age > 20")
    //val teenagers = spark.sql("select * from people where age > 12 AND age < 31 AND name IN (Select employees.name from employees where employees.salary > 3500)")
    //val teenagers = spark.sql("select * from people where age > 12 AND age < 31 AND name IN (Select employees.name from employees where employees.salary > 3500 AND employees.salary < 4200)");
    //val teenagers = spark.sql("Select * from people inner join employees on people.name = employees.name");
    //val teenagers = spark.sql("select count(age) from people group by name");
    //val teenagers = spark.sql("SELECT * FROM people inner join employees on people.name = employees.name where people.age > 15 AND employees.salary > 3500");
    //val teenagers = spark.sql("SELECT * FROM people inner join employees on people.name = employees.name where people.age > 15 AND employees.salary > 3500 AND employees.salary IN (select salary from employees where employees.salary > 4200)");
    //val teenagers = spark.sql("SELECT * FROM people inner join employees on people.name = employees.name where employees.salary > 3500 AND employees.salary IN (select salary from employeesCopy where employeesCopy.salary > 4200) AND people.age IN (select count(age) from peopleCopy group by name)");


    // SQL statements can be run by using the sql methods provided by sqlContext.

    //val teenagers = spark.sql("select * from aircrafts_data where aircrafts_data.range > 3000");
    //val teenagers = spark.sql("select * from aircrafts_data where aircrafts_data.range > 3000 or aircrafts_data.range < 1300")
    //val teenagers = spark.sql("select * from aircrafts_data where aircrafts_data.range > 3000 AND aircrafts_data.range < 10000")
    //val teenagers = spark.sql("select * from aircrafts_data where aircrafts_data.range > 3000 AND aircrafts_data.range < 10000 AND aircrafts_data.aircraft_code IN (Select aircraft_code from seats where aircraft_code > 400)");
    //val teenagers = spark.sql("select * from aircrafts_data where aircrafts_data.range > 3000 AND aircrafts_data.range < 10000 AND aircrafts_data.aircraft_code IN (Select aircraft_code from seats where aircraft_code > 400 AND aircraft_code < 750)");
    //val teenagers = spark.sql("Select * from aircrafts_data inner join seats on aircrafts_data.aircraft_code = seats.aircraft_code");
    //val teenagers = spark.sql("select count(boarding_no) from boarding_passes group by flight_id)");
    //val teenagers = spark.sql("Select * from aircrafts_data inner join seats on aircrafts_data.aircraft_code = seats.aircraft_code where seats.fare_conditions = 'Business' AND aircrafts_data.range > 8000");
    //val teenagers = spark.sql("Select * from aircrafts_data inner join seats on aircrafts_data.aircraft_code = seats.aircraft_code where seats.fare_conditions = 'Business' AND aircrafts_data.range > 8000 AND aircrafts_data.aircraft_code IN (select aircraft_code from flights where flights.flight_id < 500)");
    val teenagers = spark.sql("Select * from aircrafts_data inner join seats on aircrafts_data.aircraft_code = seats.aircraft_code where seats.fare_conditions = 'Business' AND aircrafts_data.range > 8000 AND aircrafts_data.aircraft_code IN (select aircraft_code from flights where flights.flight_id < 500) AND aircrafts_data.range IN (select count(book_date) from bookings group by book_date)");

/*
   var subQueryListForRemovedColumns = ArrayBuffer[String]()
    val removedColumns:Seq[NamedExpression] = Seq(UnresolvedStar(None))
    var clonePlan = teenagers.queryExecution.logical.clone()
    /*var projectListSize = clonePlan.asInstanceOf[Project].projectList.size
    clonePlan.asInstanceOf[Project].projectList.drop(projectListSize)*/
    clonePlan.asInstanceOf[Project].projectList = removedColumns
*/
   var subQueryList = ArrayBuffer[SubQueryStorage]()
    val allSubQueries = {
      //clonePlan.accept(new SubQueryGeneratorVisitor, subQueryListForRemovedColumns)
      teenagers.queryExecution.analyzed.accept(new SubQueryGeneratorVisitor, subQueryList)
    }
    //allSubQueries.prependAll(subQueryListForRemovedColumns)
    allSubQueries.foreach { x =>
      subQueryAcc.add(x)
      println(x)
    }

    //teenagers.show()
    //teenagers.debug()
    //teenagers.toJavaRDD()
    val stack : mutable.ArrayStack[SparkPlan] = mutable.ArrayStack()
    stack.push(teenagers.queryExecution.executedPlan)
    // teenagers.queryExecution.executedPlan.children.map(c => stack.push(c))
    var storeSchema = new StructType()
    var list_taps = List[TapImpl]()
    while(stack.nonEmpty) {
      val currentPlan = stack.pop()
      currentPlan.children.map(c => stack.push(c))
      if(currentPlan.isInstanceOf[ProjectExec]){
        storeSchema = currentPlan.schema
      }
      if(!currentPlan.isInstanceOf[BroadcastHashJoinExec]) {
          var tap_br = new TapImpl(currentPlan.id, currentPlan.schema)
          currentPlan.setTap(tap_br)
          list_taps = tap_br :: list_taps
        } else {
          if(currentPlan.asInstanceOf[BroadcastHashJoinExec].right.isInstanceOf[InputAdapter]) {
            var tap_br = new TapImpl(currentPlan.id, currentPlan.asInstanceOf[BroadcastHashJoinExec].right.asInstanceOf[InputAdapter].child.schema)
            currentPlan.setTap(tap_br)
            list_taps = tap_br :: list_taps
          } else {
            var tap_br = new TapImpl(currentPlan.id, currentPlan.asInstanceOf[BroadcastHashJoinExec].left.asInstanceOf[InputAdapter].child.schema)
            currentPlan.setTap(tap_br)
            list_taps = tap_br :: list_taps
          }
        }



      //val qe = spark.sessionState.executePlan(currentPlan)
      //qe.assertAnalyzed()
      //val df : DataFrame = new Dataset[Row](spark, currentPlan, RowEncoder(qe.analyzed.schema))
      //    val a = currentPlan.clone()
      println(currentPlan)
    }
    teenagers.collect().foreach(println)
    var resultSet = ArrayBuffer[ResultClass]()
    println("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^")
    allSubQueries.foreach { x  => {
      var newRow = new ArrayBuffer[String]
      resultSet.append(new ResultClass(x.subQueryString, newRow))
      tapsAcc.value.foreach { r => {

        if (r != null) {
          var projectionListFromSchema = ""
          r.schema.foreach{schemaIter => {projectionListFromSchema = projectionListFromSchema + schemaIter.name + ","}}
          if (r.indexRecord == x.subQueryIndex && r.indexRecord != 11111) {
            resultSet.foreach(resultval => {
              if (resultval.query == x.subQueryString) {
                var newQueryWithUpdatedProjectionList = x.subQueryString.substring(0,6) + " " +(projectionListFromSchema.substring(0,projectionListFromSchema.length - 1)) + " " + x.subQueryString.substring(x.subQueryString.indexOf("from"),x.subQueryString.length)
              resultval.query = newQueryWithUpdatedProjectionList
              x.subQueryString = newQueryWithUpdatedProjectionList
                var result = ""
                resultval.results.append(r.tapRow.toSeq(r.schema).toString().substring(13,r.tapRow.toSeq(r.schema).toString().size-1))
              }
            })
          }
        }
      }}}
    }

    captureForAggregate(allSubQueries,resultSet)
    regenerateDataForOr(allSubQueries,resultSet)
    allSubQueries.foreach { x  => {
        if (x.subQueryIndex == 44444) {
          resultSet.foreach(resultval => {
            if (resultval.query == x.subQueryString) {
              var dataForJoin = regenerateDataForJoin(allSubQueries,resultSet)
              resultval.query = x.subQueryString.substring(0,x.subQueryString.indexOf("%") - 1)
              var resultsForJoin = new ArrayBuffer[String]
              var i = 0
              while(i < dataForJoin.size){
                   resultsForJoin.append(dataForJoin(i).substring(13,dataForJoin(i).size-1) + ", " +dataForJoin(i+1).substring(13,dataForJoin(i+1).size-1))
                    i += 2
              }
              resultval.results.appendAll(resultsForJoin)
            }
          })
        }
      }
    }

    resultSet.foreach(x => {
      println(x.query)
      x.results.foreach { println }
      println("**************************************")
    })
    val duration = (System.nanoTime() - t1) / 1e9d
    println(duration)
    //tapsAcc.value.foreach { r => println(r.tapRow.toSeq(r.schema).toString())}
    }
  def regenerateDataForJoin(allSubQueries: ArrayBuffer[SubQueryStorage], resultSet: ArrayBuffer[ResultClass]): ArrayBuffer[String] ={
    var leftTableMappingIndex = -1
    var rightTableMappingIndex = -1
    var conditionColumnName = ""
    var flag = false
    for((x,i) <- allSubQueries.view.zipWithIndex){
      if(x.subQueryIndex == 44444){
        conditionColumnName = allSubQueries(i).subQueryString.substring((x.subQueryString indexOf "on Some((") + 9,x.subQueryString.size)
        conditionColumnName = conditionColumnName.substring(0,conditionColumnName indexOf("#"))
        var stringIndeces = allSubQueries(i).subQueryString.split("%")
        leftTableMappingIndex = stringIndeces(1).toInt
        rightTableMappingIndex = stringIndeces(2).toInt
        flag= true
      }
    }
    var resultSet = new ArrayBuffer[String]()
    if(flag) {
      for (i <- tapsAcc.value) {
        if(i!=null){
        if (i.indexRecord == leftTableMappingIndex) {
          for (j <- tapsAcc.value) {
            if(j!= null){
            if (j.indexRecord == rightTableMappingIndex) {
              var conditionColumnIndexLeft = i.schema.indexWhere(iter => iter.name.toString == conditionColumnName.toString)
              var conditionColumnIndexRight = j.schema.indexWhere(iter => iter.name.toString == conditionColumnName.toString)
              var conditionCheckValueLeft = i.tapRow.toSeq(i.schema)(conditionColumnIndexLeft)
              var conditionCheckValueRight = j.tapRow.toSeq(j.schema)(conditionColumnIndexRight)
              if (conditionCheckValueLeft == conditionCheckValueRight) {
                var fields = new ArrayBuffer[StructField]
                i.schema.fields.foreach { r => fields.append(r) }
                j.schema.fields.foreach { r => fields.append(r) }
                var newSchema = new StructType(fields.toArray)
                /*  var leftRow = newRow.asInstanceOf[GenericInternalRow].values(0).asInstanceOf[InternalRow].toSeq(i.schema)
                var rightRow = newRow.asInstanceOf[GenericInternalRow].values(1).asInstanceOf[InternalRow].toSeq(j.schema)*/
                resultSet.append(i.tapRow.toSeq(i.schema).toString())
                resultSet.append(j.tapRow.toSeq(j.schema).toString())
              }
            }
          }
        }
        }
      }
    }
    }
    resultSet
  }
  def regenerateDataForOr(allSubQueries: ArrayBuffer[SubQueryStorage], resultSet: ArrayBuffer[ResultClass]): Unit ={
    for((x,i) <- allSubQueries.view.zipWithIndex){
      if(x.subQueryIndex == 22222){
        var allDataMappingIndex = allSubQueries(i-1).subQueryIndex
        var resultColumnIndex = -1
        var conditionValue = ""
        var orCondition = x.subQueryString.substring((x.subQueryString indexOf "where") + 5,x.subQueryString.length)
        var columnName = orCondition.substring(1,orCondition.indexOf("#"))
        var symbol = ""
        var symbolArray = ArrayBuffer[String](">","<","=")
        for(y <- symbolArray){
          var symbolIndexVal = orCondition.indexOf(y)
          if(symbolIndexVal != -1){
            symbol = y
            conditionValue = orCondition.substring(symbolIndexVal + 2,orCondition.length)
          }
        }
        var conditionColumnSchema = tapsAcc.value(tapsAcc.value.indexWhere(element => element.indexRecord == allDataMappingIndex)).schema
        var conditionColumnIndex = conditionColumnSchema.indexWhere(iter => iter.name.toString == columnName.toString)
        var dataTypeForComparison = conditionColumnSchema(0).dataType.toString.replace("Type","")

        tapsAcc.value.foreach { r => {
          if(r!=null) {
            if (r.indexRecord == allDataMappingIndex) {
              var conditionCheckValue = r.tapRow.toSeq(r.schema)(conditionColumnIndex)
              if (conditionCheckValue.isInstanceOf[Long]) {
                var typedConditionValue = conditionValue.toLong
                if (symbol == "<") {
                  if (conditionCheckValue.asInstanceOf[Long] < typedConditionValue) {
                    resultSet.foreach(resultval => {
                      if (resultval.query == x.subQueryString) {
                        var result = ""
                        resultval.results.append(r.tapRow.toSeq(r.schema).toString().substring(13,r.tapRow.toSeq(r.schema).toString().size-1))
                      }
                    })
                  }
                } else if (symbol == ">") {
                  if (conditionCheckValue.asInstanceOf[Long] > typedConditionValue) {
                    resultSet.foreach(resultval => {
                      if (resultval.query == x.subQueryString) {
                        var result = ""
                        resultval.results.append(r.tapRow.toSeq(r.schema).toString().substring(13,r.tapRow.toSeq(r.schema).toString().size-1))
                      }
                    })
                  }
                } else if (symbol == "=") {
                  if (conditionCheckValue.asInstanceOf[Long] == typedConditionValue) {
                    resultSet.foreach(resultval => {
                      if (resultval.query == x.subQueryString) {
                        var result = ""
                        resultval.results.append(r.tapRow.toSeq(r.schema).toString().substring(13,r.tapRow.toSeq(r.schema).toString().size-1))
                      }
                    })
                  }
                }
              }
            }
          }
        }}
      }}
  }
  def captureForAggregate(allSubQueries: ArrayBuffer[SubQueryStorage], resultSet: ArrayBuffer[ResultClass]): Unit ={
    var aggRes = 0
    tapsAcc.value.foreach { r => {
      if(r!=null) {
        if (r.indexRecord == 11111) {
          var addValue = r.tapRow.toSeq(r.schema)(0).toString().toInt
          aggRes = aggRes + addValue
        }
      }
    }}
    allSubQueries.foreach { x  => {
      if(x.subQueryIndex == 11111){
        resultSet.foreach(resultval => {
          if(resultval.query == x.subQueryString){
            resultval.results.append(InternalRow(aggRes).toString)
          }
        })
      }}}
  }
case class RowRecord(id:Int, schema:StructType,tapRow:InternalRow, indexRecord:Int)

  class TapsAccumulatorV2 extends AccumulatorV2[RowRecord,ArrayBuffer[RowRecord]] {

    @transient private var accumTaps = new ArrayBuffer[RowRecord]()

    def reset(): Unit = {
      accumTaps = new ArrayBuffer[RowRecord]()
    }

    def add(v: RowRecord): Unit = {
      accumTaps.append(v)
    }

    override def isZero: Boolean = (accumTaps.isEmpty)

    override def copy(): AccumulatorV2[RowRecord, ArrayBuffer[RowRecord]] = {
      val copyAccumTaps = new TapsAccumulatorV2
      copyAccumTaps.accumTaps = accumTaps
      copyAccumTaps
    }

    override def merge(other: AccumulatorV2[RowRecord, ArrayBuffer[RowRecord]]): Unit = {
      other match {
        case any: TapsAccumulatorV2 =>
          accumTaps ++= any.value.diff(this.accumTaps)
        case _ => throw new UnsupportedOperationException(s"cannot merge ${this.getClass.getName} with ${other.getClass.getName}")
      }
    }

    override def value: ArrayBuffer[RowRecord] = accumTaps
  }

  class SubQueryAccumulatorV2 extends AccumulatorV2[SubQueryStorage,ArrayBuffer[SubQueryStorage]] {

    @transient private var accumSubQuery = new ArrayBuffer[SubQueryStorage]()

    def reset(): Unit = {
      accumSubQuery = new ArrayBuffer[SubQueryStorage]()
    }

    def add(v: SubQueryStorage): Unit = {
      accumSubQuery.append(v)
    }

    override def isZero: Boolean = (accumSubQuery.isEmpty)

    override def copy(): AccumulatorV2[SubQueryStorage, ArrayBuffer[SubQueryStorage]] = {
      val copyAccumSubQuery = new SubQueryAccumulatorV2
      copyAccumSubQuery.accumSubQuery = accumSubQuery
      copyAccumSubQuery
    }

    override def merge(other: AccumulatorV2[SubQueryStorage, ArrayBuffer[SubQueryStorage]]): Unit = {
      other match {
        case any: SubQueryAccumulatorV2 =>
          accumSubQuery ++= any.value.diff(this.accumSubQuery)
        case _ => throw new UnsupportedOperationException(s"cannot merge ${this.getClass.getName} with ${other.getClass.getName}")
      }
    }

    override def value: ArrayBuffer[SubQueryStorage] = accumSubQuery
  }



  class TapImpl(id:Int, schema:StructType) extends TapInterface[InternalRow]{
    var string = "null "
    var count = 0

    println("Start Resetting taps" + id)
    val taps = new ArrayBuffer[InternalRow]()
    println("Done Resetting taps" + id)


    override def tapRecord(r: InternalRow, indexRecord:Int): InternalRow = {
      synchronized {
        if(checkCounter(indexRecord)){
        var generatedSchema = new StructType()
        if(indexRecord == 11111) {
          generatedSchema = new StructType().add("aggVal", IntegerType)
        }else {
          generatedSchema = schema
        }
          this.taps.append(r)
          this.string = r.toSeq(generatedSchema).toString()
          println(id + " --> " + r.toSeq(generatedSchema))
          tapsAcc.add(RowRecord(id, generatedSchema, r.copy(), indexRecord))
      }
      r
    }}
    def checkCounter(indexRecord:Int): Boolean ={
      subQueryAcc.value.foreach{x =>
        if(x.subQueryIndex == 22222 && indexRecord == 22222){
          return false
        }
      }
      if(this.count % 10 == 0) {
        this.count += 1
        return true
      }
      this.count += 1
      false
    }
    def getSchema = {schema}
    def getID={id}
    def getString(): String ={
      return string
    }
    override def clone(): TapImpl = {
      val a = new TapImpl(id, schema)
      taps.map(s => a.tapRecord(s,0))
      a
    }
  }
}

