package org.apache.spark.sdb


case class SubQueryStorage(var subQueryString: String,var subQueryIndex: Int = 0,var regenFlag:Boolean = false, var leftTableFieldIndex:Int = 0, var rightTableFieldIndex:Int = 0)
