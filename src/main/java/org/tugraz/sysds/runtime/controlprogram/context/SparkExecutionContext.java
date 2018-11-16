/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.tugraz.sysds.runtime.controlprogram.context;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.RDDInfo;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.util.LongAccumulator;
import org.tugraz.sysds.api.DMLScript;
import org.tugraz.sysds.api.DMLScript.RUNTIME_PLATFORM;
import org.tugraz.sysds.api.mlcontext.MLContext;
import org.tugraz.sysds.api.mlcontext.MLContextUtil;
import org.tugraz.sysds.conf.ConfigurationManager;
import org.tugraz.sysds.hops.OptimizerUtils;
import org.tugraz.sysds.lops.Checkpoint;
import org.tugraz.sysds.common.Types.ValueType;
import org.tugraz.sysds.runtime.DMLRuntimeException;
import org.tugraz.sysds.runtime.controlprogram.Program;
import org.tugraz.sysds.runtime.controlprogram.caching.CacheBlock;
import org.tugraz.sysds.runtime.controlprogram.caching.CacheableData;
import org.tugraz.sysds.runtime.controlprogram.caching.FrameObject;
import org.tugraz.sysds.runtime.controlprogram.caching.MatrixObject;
import org.tugraz.sysds.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import org.tugraz.sysds.runtime.data.SparseBlock;
import org.tugraz.sysds.runtime.instructions.cp.Data;
import org.tugraz.sysds.runtime.instructions.spark.data.BroadcastObject;
import org.tugraz.sysds.runtime.instructions.spark.data.LineageObject;
import org.tugraz.sysds.runtime.instructions.spark.data.PartitionedBlock;
import org.tugraz.sysds.runtime.instructions.spark.data.PartitionedBroadcast;
import org.tugraz.sysds.runtime.instructions.spark.data.RDDObject;
import org.tugraz.sysds.runtime.instructions.spark.functions.ComputeBinaryBlockNnzFunction;
import org.tugraz.sysds.runtime.instructions.spark.functions.CopyBinaryCellFunction;
import org.tugraz.sysds.runtime.instructions.spark.functions.CopyFrameBlockPairFunction;
import org.tugraz.sysds.runtime.instructions.spark.functions.CopyTextInputFunction;
import org.tugraz.sysds.runtime.instructions.spark.functions.CreateSparseBlockFunction;
import org.tugraz.sysds.runtime.instructions.spark.utils.RDDAggregateUtils;
import org.tugraz.sysds.runtime.instructions.spark.utils.SparkUtils;
import org.tugraz.sysds.runtime.instructions.spark.utils.FrameRDDConverterUtils.LongFrameToLongWritableFrameFunction;
import org.tugraz.sysds.runtime.matrix.data.FrameBlock;
import org.tugraz.sysds.runtime.matrix.data.InputInfo;
import org.tugraz.sysds.runtime.matrix.data.MatrixBlock;
import org.tugraz.sysds.runtime.matrix.data.MatrixCell;
import org.tugraz.sysds.runtime.matrix.data.MatrixIndexes;
import org.tugraz.sysds.runtime.matrix.data.OutputInfo;
import org.tugraz.sysds.runtime.matrix.mapred.MRJobConfiguration;
import org.tugraz.sysds.runtime.meta.MatrixCharacteristics;
import org.tugraz.sysds.runtime.util.HDFSTool;
import org.tugraz.sysds.runtime.util.UtilFunctions;
import org.tugraz.sysds.utils.MLContextProxy;
import org.tugraz.sysds.utils.Statistics;

import scala.Tuple2;


public class SparkExecutionContext extends ExecutionContext
{
	private static final Log LOG = LogFactory.getLog(SparkExecutionContext.class.getName());
	private static final boolean LDEBUG = false; //local debug flag

	//internal configurations
	private static final boolean LAZY_SPARKCTX_CREATION = true;
	private static final boolean ASYNCHRONOUS_VAR_DESTROY = true;
	public static final boolean FAIR_SCHEDULER_MODE = true;

	//executor memory and relative fractions as obtained from the spark configuration
	private static SparkClusterConfig _sconf = null;

	//singleton spark context (as there can be only one spark context per JVM)
	private static JavaSparkContext _spctx = null;

	//registry of parallelized RDDs to enforce that at any time, we spent at most
	//10% of JVM max heap size for parallelized RDDs; if this is not sufficient,
	//matrices or frames are exported to HDFS and the RDDs are created from files.
	//TODO unify memory management for CP, par RDDs, and potentially broadcasts
	private static final MemoryManagerParRDDs _parRDDs = new MemoryManagerParRDDs(0.1);
	
	//pool of reused fair scheduler pool names (unset bits indicate availability)
	private static boolean[] _poolBuff = FAIR_SCHEDULER_MODE ?
		new boolean[InfrastructureAnalyzer.getLocalParallelism()] : null;
	
	static {
		// for internal debugging only
		if( LDEBUG ) {
			Logger.getLogger("org.apache.sysml.runtime.controlprogram.context")
				  .setLevel((Level) Level.DEBUG);
		}
	}

	protected SparkExecutionContext(boolean allocateVars, Program prog) {
		//protected constructor to force use of ExecutionContextFactory
		super( allocateVars, prog );
		
		//spark context creation via internal initializer
		if( !LAZY_SPARKCTX_CREATION || DMLScript.rtplatform==RUNTIME_PLATFORM.SPARK ) {
			initSparkContext();
		}
	}

	/**
	 * Returns the used singleton spark context. In case of lazy spark context
	 * creation, this methods blocks until the spark context is created.
	 *
	 * @return java spark context
	 */
	public JavaSparkContext getSparkContext() {
		//lazy spark context creation on demand (lazy instead of asynchronous
		//to avoid wait for uninitialized spark context on close)
		if( LAZY_SPARKCTX_CREATION ) {
			initSparkContext();
		}

		//return the created spark context
		return _spctx;
	}

	public static JavaSparkContext getSparkContextStatic() {
		initSparkContext();
		return _spctx;
	}

	/**
	 * Indicates if the spark context has been created or has
	 * been passed in from outside.
	 *
	 * @return true if spark context created
	 */
	public synchronized static boolean isSparkContextCreated() {
		return (_spctx != null);
	}

	public static void resetSparkContextStatic() {
		_spctx = null;
	}

	public void close()
	{
		synchronized( SparkExecutionContext.class ) {
			if( _spctx != null )
			{
				//stop the spark context if existing
				_spctx.stop();

				//make sure stopped context is never used again
				_spctx = null;
			}

		}
	}

	public static boolean isLazySparkContextCreation(){
		return LAZY_SPARKCTX_CREATION;
	}

	private synchronized static void initSparkContext()
	{
		//check for redundant spark context init
		if( _spctx != null )
			return;

		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		//create a default spark context (master, appname, etc refer to system properties
		//as given in the spark configuration or during spark-submit)

		MLContext mlCtxObj = MLContextProxy.getActiveMLContext();
		if(mlCtxObj != null)
		{
			// This is when DML is called through spark shell
			// Will clean the passing of static variables later as this involves minimal change to DMLScript
			_spctx = MLContextUtil.getJavaSparkContext(mlCtxObj);
		}
		else
		{
			if(DMLScript.USE_LOCAL_SPARK_CONFIG) {
				// For now set 4 cores for integration testing :)
				SparkConf conf = createSystemMLSparkConf()
						.setMaster("local[*]").setAppName("My local integration test app");
				// This is discouraged in spark but have added only for those testcase that cannot stop the context properly
				// conf.set("spark.driver.allowMultipleContexts", "true");
				conf.set("spark.ui.enabled", "false");
				_spctx = new JavaSparkContext(conf);
			}
			else //default cluster setup
			{
				//setup systemml-preferred spark configuration (w/o user choice)
				SparkConf conf = createSystemMLSparkConf();
				_spctx = new JavaSparkContext(conf);
			}

			_parRDDs.clear();
		}

		// Set warning if spark.driver.maxResultSize is not set. It needs to be set before starting Spark Context for CP collect
		String strDriverMaxResSize = _spctx.getConf().get("spark.driver.maxResultSize", "1g");
		long driverMaxResSize = UtilFunctions.parseMemorySize(strDriverMaxResSize);
		if (driverMaxResSize != 0 && driverMaxResSize<OptimizerUtils.getLocalMemBudget() && !DMLScript.USE_LOCAL_SPARK_CONFIG)
			LOG.warn("Configuration parameter spark.driver.maxResultSize set to " + UtilFunctions.formatMemorySize(driverMaxResSize) + "."
					+ " You can set it through Spark default configuration setting either to 0 (unlimited) or to available memory budget of size "
					+ UtilFunctions.formatMemorySize((long)OptimizerUtils.getLocalMemBudget()) + ".");

		//globally add binaryblock serialization framework for all hdfs read/write operations
		//TODO if spark context passed in from outside (mlcontext), we need to clean this up at the end
		if( MRJobConfiguration.USE_BINARYBLOCK_SERIALIZATION )
			MRJobConfiguration.addBinaryBlockSerializationFramework( _spctx.hadoopConfiguration() );

		//statistics maintenance
		if( DMLScript.STATISTICS ){
			Statistics.setSparkCtxCreateTime(System.nanoTime()-t0);
		}
	}

	/**
	 * Sets up a SystemML-preferred Spark configuration based on the implicit
	 * default configuration (as passed via configurations from outside).
	 *
	 * @return spark configuration
	 */
	public static SparkConf createSystemMLSparkConf() {
		SparkConf conf = new SparkConf();

		//always set unlimited result size (required for cp collect)
		conf.set("spark.driver.maxResultSize", "0");

		//always use the fair scheduler (for single jobs, it's equivalent to fifo
		//but for concurrent jobs in parfor it ensures better data locality because
		//round robin assignment mitigates the problem of 'sticky slots')
		if( FAIR_SCHEDULER_MODE ) {
			conf.set("spark.scheduler.mode", "FAIR");
		}

		//increase scheduler delay (usually more robust due to better data locality)
		if( !conf.contains("spark.locality.wait") ) { //default 3s
			conf.set("spark.locality.wait", "5s");
		}
		
		//increase max message size for robustness
		String sparkVersion = org.apache.spark.package$.MODULE$.SPARK_VERSION();
		String msgSizeConf = (UtilFunctions.compareVersion(sparkVersion, "2.0.0") < 0) ?
			"spark.akka.frameSize" : "spark.rpc.message.maxSize";
		if( !conf.contains(msgSizeConf) ) { //default 128MB
			conf.set(msgSizeConf, "512");
		}
		
		return conf;
	}
	
	public static boolean isLocalMaster() {
		return getSparkContextStatic().isLocal();
	}

	/**
	 * Spark instructions should call this for all matrix inputs except broadcast
	 * variables.
	 *
	 * @param varname variable name
	 * @return JavaPairRDD of MatrixIndexes-MatrixBlocks
	 */
	@SuppressWarnings("unchecked")
	public JavaPairRDD<MatrixIndexes,MatrixBlock> getBinaryBlockRDDHandleForVariable( String varname ) {
		MatrixObject mo = getMatrixObject(varname);
		return (JavaPairRDD<MatrixIndexes,MatrixBlock>)
			getRDDHandleForMatrixObject(mo, InputInfo.BinaryBlockInputInfo, -1, true);
	}
	
	@SuppressWarnings("unchecked")
	public JavaPairRDD<MatrixIndexes,MatrixBlock> getBinaryBlockRDDHandleForVariable( String varname, int numParts, boolean inclEmpty ) {
		MatrixObject mo = getMatrixObject(varname);
		return (JavaPairRDD<MatrixIndexes,MatrixBlock>)
			getRDDHandleForMatrixObject(mo, InputInfo.BinaryBlockInputInfo, numParts, inclEmpty);
	}

	/**
	 * Spark instructions should call this for all frame inputs except broadcast
	 * variables.
	 *
	 * @param varname variable name
	 * @return JavaPairRDD of Longs-FrameBlocks
	 */
	@SuppressWarnings("unchecked")
	public JavaPairRDD<Long,FrameBlock> getFrameBinaryBlockRDDHandleForVariable( String varname ) {
		FrameObject fo = getFrameObject(varname);
		JavaPairRDD<Long,FrameBlock> out = (JavaPairRDD<Long,FrameBlock>)
			getRDDHandleForFrameObject(fo, InputInfo.BinaryBlockInputInfo);
		return out;
	}

	public JavaPairRDD<?,?> getRDDHandleForVariable( String varname, InputInfo inputInfo, int numParts, boolean inclEmpty ) {
		Data dat = getVariable(varname);
		if( dat instanceof MatrixObject ) {
			MatrixObject mo = getMatrixObject(varname);
			return getRDDHandleForMatrixObject(mo, inputInfo, numParts, inclEmpty);
		}
		else if( dat instanceof FrameObject ) {
			FrameObject fo = getFrameObject(varname);
			return getRDDHandleForFrameObject(fo, inputInfo);
		}
		else {
			throw new DMLRuntimeException("Failed to obtain RDD for data type other than matrix or frame.");
		}
	}

	public JavaPairRDD<?,?> getRDDHandleForMatrixObject( MatrixObject mo, InputInfo inputInfo ) {
		return getRDDHandleForMatrixObject(mo, inputInfo, -1, true);
	}
	
	@SuppressWarnings("unchecked")
	public JavaPairRDD<?,?> getRDDHandleForMatrixObject( MatrixObject mo, InputInfo inputInfo, int numParts, boolean inclEmpty ) {
		//NOTE: MB this logic should be integrated into MatrixObject
		//However, for now we cannot assume that spark libraries are
		//always available and hence only store generic references in
		//matrix object while all the logic is in the SparkExecContext

		JavaSparkContext sc = getSparkContext();
		JavaPairRDD<?,?> rdd = null;
		//CASE 1: rdd already existing (reuse if checkpoint or trigger
		//pending rdd operations if not yet cached but prevent to re-evaluate
		//rdd operations if already executed and cached
		if(    mo.getRDDHandle()!=null
			&& (mo.getRDDHandle().isCheckpointRDD() || !mo.isCached(false)) )
		{
			//return existing rdd handling (w/o input format change)
			rdd = mo.getRDDHandle().getRDD();
		}
		//CASE 2: dirty in memory data or cached result of rdd operations
		else if( mo.isDirty() || mo.isCached(false) )
		{
			//get in-memory matrix block and parallelize it
			//w/ guarded parallelize (fallback to export, rdd from file if too large)
			MatrixCharacteristics mc = mo.getMatrixCharacteristics();
			boolean fromFile = false;
			if( !OptimizerUtils.checkSparkCollectMemoryBudget(mc, 0) || !_parRDDs.reserve(
					OptimizerUtils.estimatePartitionedSizeExactSparsity(mc))) {
				if( mo.isDirty() || !mo.isHDFSFileExists() ) //write if necessary
					mo.exportData();
				rdd = sc.hadoopFile( mo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = SparkUtils.copyBinaryBlockMatrix((JavaPairRDD<MatrixIndexes, MatrixBlock>)rdd); //cp is workaround for read bug
				fromFile = true;
			}
			else { //default case
				MatrixBlock mb = mo.acquireRead(); //pin matrix in memory
				rdd = toMatrixJavaPairRDD(sc, mb, (int)mo.getNumRowsPerBlock(), (int)mo.getNumColumnsPerBlock(), numParts, inclEmpty);
				mo.release(); //unpin matrix
				_parRDDs.registerRDD(rdd.id(), OptimizerUtils.estimatePartitionedSizeExactSparsity(mc), true);
			}

			//keep rdd handle for future operations on it
			RDDObject rddhandle = new RDDObject(rdd);
			rddhandle.setHDFSFile(fromFile);
			rddhandle.setParallelizedRDD(!fromFile);
			mo.setRDDHandle(rddhandle);
		}
		//CASE 3: non-dirty (file exists on HDFS)
		else
		{
			// parallelize hdfs-resident file
			// For binary block, these are: SequenceFileInputFormat.class, MatrixIndexes.class, MatrixBlock.class
			if(inputInfo == InputInfo.BinaryBlockInputInfo) {
				rdd = sc.hadoopFile( mo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				//note: this copy is still required in Spark 1.4 because spark hands out whatever the inputformat
				//recordreader returns; the javadoc explicitly recommend to copy all key/value pairs
				rdd = SparkUtils.copyBinaryBlockMatrix((JavaPairRDD<MatrixIndexes, MatrixBlock>)rdd); //cp is workaround for read bug
			}
			else if(inputInfo == InputInfo.TextCellInputInfo || inputInfo == InputInfo.CSVInputInfo || inputInfo == InputInfo.MatrixMarketInputInfo) {
				rdd = sc.hadoopFile( mo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = ((JavaPairRDD<LongWritable, Text>)rdd).mapToPair( new CopyTextInputFunction() ); //cp is workaround for read bug
			}
			else if(inputInfo == InputInfo.BinaryCellInputInfo) {
				rdd = sc.hadoopFile( mo.getFileName(), inputInfo.inputFormatClass, inputInfo.inputKeyClass, inputInfo.inputValueClass);
				rdd = ((JavaPairRDD<MatrixIndexes, MatrixCell>)rdd).mapToPair( new CopyBinaryCellFunction() ); //cp is workaround for read bug
			}
			else {
				throw new DMLRuntimeException("Incorrect input format in getRDDHandleForVariable");
			}

			//keep rdd handle for future operations on it
			RDDObject rddhandle = new RDDObject(rdd);
			rddhandle.setHDFSFile(true);
			mo.setRDDHandle(rddhandle);
		}

		return rdd;
	}

	/**
	 * FIXME: currently this implementation assumes matrix representations but frame signature
	 * in order to support the old transform implementation.
	 *
	 * @param fo frame object
	 * @param inputInfo input info
	 * @return JavaPairRDD handle for a frame object
	 */
	@SuppressWarnings("unchecked")
	public JavaPairRDD<?,?> getRDDHandleForFrameObject( FrameObject fo, InputInfo inputInfo )
	{
		//NOTE: MB this logic should be integrated into FrameObject
		//However, for now we cannot assume that spark libraries are
		//always available and hence only store generic references in
		//matrix object while all the logic is in the SparkExecContext

		InputInfo inputInfo2 = (inputInfo==InputInfo.BinaryBlockInputInfo) ?
				InputInfo.BinaryBlockFrameInputInfo : inputInfo;

		JavaSparkContext sc = getSparkContext();
		JavaPairRDD<?,?> rdd = null;
		//CASE 1: rdd already existing (reuse if checkpoint or trigger
		//pending rdd operations if not yet cached but prevent to re-evaluate
		//rdd operations if already executed and cached
		if(    fo.getRDDHandle()!=null
			&& (fo.getRDDHandle().isCheckpointRDD() || !fo.isCached(false)) )
		{
			//return existing rdd handling (w/o input format change)
			rdd = fo.getRDDHandle().getRDD();
		}
		//CASE 2: dirty in memory data or cached result of rdd operations
		else if( fo.isDirty() || fo.isCached(false) )
		{
			//get in-memory matrix block and parallelize it
			//w/ guarded parallelize (fallback to export, rdd from file if too large)
			MatrixCharacteristics mc = fo.getMatrixCharacteristics();
			boolean fromFile = false;
			if( !OptimizerUtils.checkSparkCollectMemoryBudget(mc, 0) || !_parRDDs.reserve(
					OptimizerUtils.estimatePartitionedSizeExactSparsity(mc)) ) {
				if( fo.isDirty() ) { //write only if necessary
					fo.exportData();
				}
				rdd = sc.hadoopFile( fo.getFileName(), inputInfo2.inputFormatClass, inputInfo2.inputKeyClass, inputInfo2.inputValueClass);
				rdd = ((JavaPairRDD<LongWritable, FrameBlock>)rdd).mapToPair( new CopyFrameBlockPairFunction() ); //cp is workaround for read bug
				fromFile = true;
			}
			else { //default case
				FrameBlock fb = fo.acquireRead(); //pin frame in memory
				rdd = toFrameJavaPairRDD(sc, fb);
				fo.release(); //unpin frame
				_parRDDs.registerRDD(rdd.id(), OptimizerUtils.estimatePartitionedSizeExactSparsity(mc), true);
			}

			//keep rdd handle for future operations on it
			RDDObject rddhandle = new RDDObject(rdd);
			rddhandle.setHDFSFile(fromFile);
			fo.setRDDHandle(rddhandle);
		}
		//CASE 3: non-dirty (file exists on HDFS)
		else
		{
			// parallelize hdfs-resident file
			// For binary block, these are: SequenceFileInputFormat.class, MatrixIndexes.class, MatrixBlock.class
			if(inputInfo2 == InputInfo.BinaryBlockFrameInputInfo) {
				rdd = sc.hadoopFile( fo.getFileName(), inputInfo2.inputFormatClass, inputInfo2.inputKeyClass, inputInfo2.inputValueClass);
				//note: this copy is still required in Spark 1.4 because spark hands out whatever the inputformat
				//recordreader returns; the javadoc explicitly recommend to copy all key/value pairs
				rdd = ((JavaPairRDD<LongWritable, FrameBlock>)rdd).mapToPair( new CopyFrameBlockPairFunction() ); //cp is workaround for read bug
			}
			else if(inputInfo2 == InputInfo.TextCellInputInfo || inputInfo2 == InputInfo.CSVInputInfo || inputInfo2 == InputInfo.MatrixMarketInputInfo) {
				rdd = sc.hadoopFile( fo.getFileName(), inputInfo2.inputFormatClass, inputInfo2.inputKeyClass, inputInfo2.inputValueClass);
				rdd = ((JavaPairRDD<LongWritable, Text>)rdd).mapToPair( new CopyTextInputFunction() ); //cp is workaround for read bug
			}
			else if(inputInfo2 == InputInfo.BinaryCellInputInfo) {
				throw new DMLRuntimeException("Binarycell not supported for frames.");
			}
			else {
				throw new DMLRuntimeException("Incorrect input format in getRDDHandleForVariable");
			}

			//keep rdd handle for future operations on it
			RDDObject rddhandle = new RDDObject(rdd);
			rddhandle.setHDFSFile(true);
			fo.setRDDHandle(rddhandle);
		}

		return rdd;
	}

	public Broadcast<CacheBlock> broadcastVariable(CacheableData<CacheBlock> cd) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		Broadcast<CacheBlock> brBlock = null;

		// reuse existing non partitioned broadcast handle
		if (cd.getBroadcastHandle() != null && cd.getBroadcastHandle().isNonPartitionedBroadcastValid()) {
			brBlock = cd.getBroadcastHandle().getNonPartitionedBroadcast();
		}

		if (brBlock == null) {
			//create new broadcast handle (never created, evicted)
			// account for overwritten invalid broadcast (e.g., evicted)
			if (cd.getBroadcastHandle() != null)
				CacheableData.addBroadcastSize(-cd.getBroadcastHandle().getNonPartitionedBroadcastSize());

			// read the matrix block
			CacheBlock cb = cd.acquireRead();
			cd.release();

			// broadcast a non-empty frame whose size is smaller than 2G
			if (cb.getExactSerializedSize() > 0 && cb.getExactSerializedSize() <= Integer.MAX_VALUE) {
				brBlock = getSparkContext().broadcast(cb);
				// create the broadcast handle if the matrix or frame has never been broadcasted
				if (cd.getBroadcastHandle() == null) {
					cd.setBroadcastHandle(new BroadcastObject<>());
				}
				cd.getBroadcastHandle().setNonPartitionedBroadcast(brBlock,
					OptimizerUtils.estimateSize(cd.getMatrixCharacteristics()));
				CacheableData.addBroadcastSize(cd.getBroadcastHandle().getNonPartitionedBroadcastSize());

				if (DMLScript.STATISTICS) {
					Statistics.accSparkBroadCastTime(System.nanoTime() - t0);
					Statistics.incSparkBroadcastCount(1);
				}
			}
		}
		return brBlock;
	}

	@SuppressWarnings("unchecked")
	public PartitionedBroadcast<MatrixBlock> getBroadcastForMatrixObject(MatrixObject mo) {
		//NOTE: The memory consumption of this method is the in-memory size of the 
		//matrix object plus the partitioned size in 1k-1k blocks. Since the call
		//to broadcast happens after the matrix object has been released, the memory
		//requirements of blockified chunks in Spark's block manager are covered under
		//this maximum. Also note that we explicitly clear the in-memory blocks once
		//the broadcasts are created (other than in local mode) in order to avoid 
		//unnecessary memory requirements during the lifetime of this broadcast handle.
		
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		PartitionedBroadcast<MatrixBlock> bret = null;

		//reuse existing broadcast handle
		if (mo.getBroadcastHandle() != null && mo.getBroadcastHandle().isPartitionedBroadcastValid()) {
			bret = mo.getBroadcastHandle().getPartitionedBroadcast();
		}

		//create new broadcast handle (never created, evicted)
		if (bret == null) {
			//account for overwritten invalid broadcast (e.g., evicted)
			if (mo.getBroadcastHandle() != null)
				CacheableData.addBroadcastSize(-mo.getBroadcastHandle().getPartitionedBroadcastSize());

			//obtain meta data for matrix
			int brlen = (int) mo.getNumRowsPerBlock();
			int bclen = (int) mo.getNumColumnsPerBlock();

			//create partitioned matrix block and release memory consumed by input
			MatrixBlock mb = mo.acquireRead();
			PartitionedBlock<MatrixBlock> pmb = new PartitionedBlock<>(mb, brlen, bclen);
			mo.release();

			//determine coarse-grained partitioning
			int numPerPart = PartitionedBroadcast.computeBlocksPerPartition(mo.getNumRows(), mo.getNumColumns(), brlen, bclen);
			int numParts = (int) Math.ceil((double) pmb.getNumRowBlocks() * pmb.getNumColumnBlocks() / numPerPart);
			Broadcast<PartitionedBlock<MatrixBlock>>[] ret = new Broadcast[numParts];

			//create coarse-grained partitioned broadcasts
			if (numParts > 1) {
				Arrays.parallelSetAll(ret, i -> createPartitionedBroadcast(pmb, numPerPart, i));
			} else { //single partition
				ret[0] = getSparkContext().broadcast(pmb);
				if (!isLocalMaster())
					pmb.clearBlocks();
			}
			
			bret = new PartitionedBroadcast<>(ret, mo.getMatrixCharacteristics());
			// create the broadcast handle if the matrix or frame has never been broadcasted
			if (mo.getBroadcastHandle() == null) {
				mo.setBroadcastHandle(new BroadcastObject<MatrixBlock>());
			}
			mo.getBroadcastHandle().setPartitionedBroadcast(bret,
				OptimizerUtils.estimatePartitionedSizeExactSparsity(mo.getMatrixCharacteristics()));
			CacheableData.addBroadcastSize(mo.getBroadcastHandle().getPartitionedBroadcastSize());
		}

		if (DMLScript.STATISTICS) {
			Statistics.accSparkBroadCastTime(System.nanoTime() - t0);
			Statistics.incSparkBroadcastCount(1);
		}

		return bret;
	}

	public PartitionedBroadcast<MatrixBlock> getBroadcastForVariable(String varname) {
		MatrixObject mo = getMatrixObject(varname);
		return getBroadcastForMatrixObject(mo);
	}

	@SuppressWarnings("unchecked")
	public PartitionedBroadcast<FrameBlock> getBroadcastForFrameVariable(String varname) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		FrameObject fo = getFrameObject(varname);

		PartitionedBroadcast<FrameBlock> bret = null;

		//reuse existing broadcast handle
		if (fo.getBroadcastHandle() != null && fo.getBroadcastHandle().isPartitionedBroadcastValid()) {
			bret = fo.getBroadcastHandle().getPartitionedBroadcast();
		}

		//create new broadcast handle (never created, evicted)
		if (bret == null) {
			//account for overwritten invalid broadcast (e.g., evicted)
			if (fo.getBroadcastHandle() != null)
				CacheableData.addBroadcastSize(-fo.getBroadcastHandle().getPartitionedBroadcastSize());

			//obtain meta data for frame
			int bclen = (int) fo.getNumColumns();
			int brlen = OptimizerUtils.getDefaultFrameSize();

			//create partitioned frame block and release memory consumed by input
			FrameBlock mb = fo.acquireRead();
			PartitionedBlock<FrameBlock> pmb = new PartitionedBlock<>(mb, brlen, bclen);
			fo.release();

			//determine coarse-grained partitioning
			int numPerPart = PartitionedBroadcast.computeBlocksPerPartition(fo.getNumRows(), fo.getNumColumns(), brlen, bclen);
			int numParts = (int) Math.ceil((double) pmb.getNumRowBlocks() * pmb.getNumColumnBlocks() / numPerPart);
			Broadcast<PartitionedBlock<FrameBlock>>[] ret = new Broadcast[numParts];

			//create coarse-grained partitioned broadcasts
			if (numParts > 1) {
				Arrays.parallelSetAll(ret, i -> createPartitionedBroadcast(pmb, numPerPart, i));
			} else { //single partition
				ret[0] = getSparkContext().broadcast(pmb);
				if (!isLocalMaster())
					pmb.clearBlocks();
			}
			
			bret = new PartitionedBroadcast<>(ret, new MatrixCharacteristics(
				fo.getMatrixCharacteristics()).setBlockSize(brlen, bclen));
			if (fo.getBroadcastHandle() == null)
				fo.setBroadcastHandle(new BroadcastObject<FrameBlock>());
			
			fo.getBroadcastHandle().setPartitionedBroadcast(bret,
				OptimizerUtils.estimatePartitionedSizeExactSparsity(fo.getMatrixCharacteristics()));
			CacheableData.addBroadcastSize(fo.getBroadcastHandle().getPartitionedBroadcastSize());
		}

		if (DMLScript.STATISTICS) {
			Statistics.accSparkBroadCastTime(System.nanoTime() - t0);
			Statistics.incSparkBroadcastCount(1);
		}

		return bret;
	}
	
	private Broadcast<PartitionedBlock<? extends CacheBlock>> createPartitionedBroadcast(
			PartitionedBlock<? extends CacheBlock> pmb, int numPerPart, int pos) {
		int offset = pos * numPerPart;
		int numBlks = Math.min(numPerPart, pmb.getNumRowBlocks() * pmb.getNumColumnBlocks() - offset);
		PartitionedBlock<? extends CacheBlock> tmp = pmb.createPartition(offset, numBlks);
		Broadcast<PartitionedBlock<? extends CacheBlock>> ret = getSparkContext().broadcast(tmp);
		if (!isLocalMaster())
			tmp.clearBlocks();
		return ret;
	}

	/**
	 * Keep the output rdd of spark rdd operations as meta data of matrix/frame
	 * objects in the symbol table.
	 *
	 * @param varname variable name
	 * @param rdd JavaPairRDD handle for variable
	 */
	public void setRDDHandleForVariable(String varname, JavaPairRDD<?,?> rdd) {
		CacheableData<?> obj = getCacheableData(varname);
		RDDObject rddhandle = new RDDObject(rdd);
		obj.setRDDHandle( rddhandle );
	}

	public static JavaPairRDD<MatrixIndexes,MatrixBlock> toMatrixJavaPairRDD(JavaSparkContext sc, MatrixBlock src, int brlen, int bclen) {
		return toMatrixJavaPairRDD(sc, src, brlen, bclen, -1, true);
	}
	
	public static JavaPairRDD<MatrixIndexes,MatrixBlock> toMatrixJavaPairRDD(JavaSparkContext sc, MatrixBlock src,
			int brlen, int bclen, int numParts, boolean inclEmpty) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		List<Tuple2<MatrixIndexes,MatrixBlock>> list = null;

		if( src.getNumRows() <= brlen && src.getNumColumns() <= bclen ) {
			list = Arrays.asList(new Tuple2<>(new MatrixIndexes(1,1), src));
		}
		else {
			MatrixCharacteristics mc = new MatrixCharacteristics(
				src.getNumRows(), src.getNumColumns(), brlen, bclen, src.getNonZeros());
			list = LongStream.range(0, mc.getNumBlocks()).parallel()
				.mapToObj(i -> createIndexedBlock(src, mc, i))
				.filter(kv -> inclEmpty || !kv._2.isEmptyBlock(false))
				.collect(Collectors.toList());
		}

		JavaPairRDD<MatrixIndexes,MatrixBlock> result = (numParts > 1) ?
			sc.parallelizePairs(list, numParts) : sc.parallelizePairs(list);
		
		if (DMLScript.STATISTICS) {
			Statistics.accSparkParallelizeTime(System.nanoTime() - t0);
			Statistics.incSparkParallelizeCount(1);
		}

		return result;
	}
	
	private static Tuple2<MatrixIndexes,MatrixBlock> createIndexedBlock(MatrixBlock mb, MatrixCharacteristics mc, long ix) {
		try {
			//compute block indexes
			long blockRow = ix / mc.getNumColBlocks();
			long blockCol = ix % mc.getNumColBlocks();
			//compute block sizes
			int maxRow = UtilFunctions.computeBlockSize(mc.getRows(), blockRow+1, mc.getRowsPerBlock());
			int maxCol = UtilFunctions.computeBlockSize(mc.getCols(), blockCol+1, mc.getColsPerBlock());
			//copy sub-matrix to block
			MatrixBlock block = new MatrixBlock(maxRow, maxCol, mb.isInSparseFormat());
			int row_offset = (int)blockRow*mc.getRowsPerBlock();
			int col_offset = (int)blockCol*mc.getColsPerBlock();
			block = mb.slice( row_offset, row_offset+maxRow-1,
				col_offset, col_offset+maxCol-1, block );
			//create key-value pair
			return new Tuple2<>(new MatrixIndexes(blockRow+1, blockCol+1), block);
		}
		catch(DMLRuntimeException ex) {
			throw new RuntimeException(ex);
		}
	}

	public static JavaPairRDD<Long,FrameBlock> toFrameJavaPairRDD(JavaSparkContext sc, FrameBlock src) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		LinkedList<Tuple2<Long,FrameBlock>> list = new LinkedList<>();

		//create and write subblocks of matrix
		int blksize = ConfigurationManager.getBlocksize();
		for(int blockRow = 0; blockRow < (int)Math.ceil(src.getNumRows()/(double)blksize); blockRow++)
		{
			int maxRow = (blockRow*blksize + blksize < src.getNumRows()) ? blksize : src.getNumRows() - blockRow*blksize;
			int roffset = blockRow*blksize;

			FrameBlock block = new FrameBlock(src.getSchema());

			//copy sub frame to block, incl meta data on first
			src.slice( roffset, roffset+maxRow-1, 0, src.getNumColumns()-1, block );
			if( roffset == 0 )
				block.setColumnMetadata(src.getColumnMetadata());

			//append block to sequence file
			list.addLast(new Tuple2<>((long)roffset+1, block));
		}

		JavaPairRDD<Long,FrameBlock> result = sc.parallelizePairs(list);
		if (DMLScript.STATISTICS) {
			Statistics.accSparkParallelizeTime(System.nanoTime() - t0);
			Statistics.incSparkParallelizeCount(1);
		}

		return result;
	}

	/**
	 * This method is a generic abstraction for calls from the buffer pool.
	 *
	 * @param rdd rdd object
	 * @param rlen number of rows
	 * @param clen number of columns
	 * @param brlen number of rows in a block
	 * @param bclen number of columns in a block
	 * @param nnz number of non-zeros
	 * @return matrix block
	 */
	@SuppressWarnings("unchecked")
	public static MatrixBlock toMatrixBlock(RDDObject rdd, int rlen, int clen, int brlen, int bclen, long nnz) {
		return toMatrixBlock(
				(JavaPairRDD<MatrixIndexes, MatrixBlock>) rdd.getRDD(),
				rlen, clen, brlen, bclen, nnz);
	}

	/**
	 * Utility method for creating a single matrix block out of a binary block RDD.
	 * Note that this collect call might trigger execution of any pending transformations.
	 *
	 * NOTE: This is an unguarded utility function, which requires memory for both the output matrix
	 * and its collected, blocked representation.
	 *
	 * @param rdd JavaPairRDD for matrix block
	 * @param rlen number of rows
	 * @param clen number of columns
	 * @param brlen number of rows in a block
	 * @param bclen number of columns in a block
	 * @param nnz number of non-zeros
	 * @return matrix block
	 */
	public static MatrixBlock toMatrixBlock(JavaPairRDD<MatrixIndexes,MatrixBlock> rdd, int rlen, int clen, int brlen, int bclen, long nnz) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		MatrixBlock out = null;

		if( rlen <= brlen && clen <= bclen ) //SINGLE BLOCK
		{
			//special case without copy and nnz maintenance
			List<Tuple2<MatrixIndexes,MatrixBlock>> list = rdd.collect();

			if( list.size()>1 )
				throw new DMLRuntimeException("Expecting no more than one result block.");
			else if( list.size()==1 )
				out = list.get(0)._2();
			else //empty (e.g., after ops w/ outputEmpty=false)
				out = new MatrixBlock(rlen, clen, true);
			out.examSparsity();
		}
		else //MULTIPLE BLOCKS
		{
			//determine target sparse/dense representation
			long lnnz = (nnz >= 0) ? nnz : (long)rlen * clen;
			boolean sparse = MatrixBlock.evalSparseFormatInMemory(rlen, clen, lnnz);

			//create output matrix block (w/ lazy allocation)
			out = new MatrixBlock(rlen, clen, sparse, lnnz);

			List<Tuple2<MatrixIndexes,MatrixBlock>> list = rdd.collect();

			//copy blocks one-at-a-time into output matrix block
			long aNnz = 0;
			for( Tuple2<MatrixIndexes,MatrixBlock> keyval : list )
			{
				//unpack index-block pair
				MatrixIndexes ix = keyval._1();
				MatrixBlock block = keyval._2();
				
				//compute row/column block offsets
				int row_offset = (int)(ix.getRowIndex()-1)*brlen;
				int col_offset = (int)(ix.getColumnIndex()-1)*bclen;
				int rows = block.getNumRows();
				int cols = block.getNumColumns();
				
				//append block
				if( sparse ) { //SPARSE OUTPUT
					//append block to sparse target in order to avoid shifting, where
					//we use a shallow row copy in case of MCSR and single column blocks
					//note: this append requires, for multiple column blocks, a final sort
					out.appendToSparse(block, row_offset, col_offset, clen>bclen);
				}
				else { //DENSE OUTPUT
					out.copy( row_offset, row_offset+rows-1,
							  col_offset, col_offset+cols-1, block, false );
				}

				//incremental maintenance nnz
				aNnz += block.getNonZeros();
			}

			//post-processing output matrix
			if( sparse && clen>bclen )
				out.sortSparseRows();
			out.setNonZeros(aNnz);
			out.examSparsity();
		}

		if (DMLScript.STATISTICS) {
			Statistics.accSparkCollectTime(System.nanoTime() - t0);
			Statistics.incSparkCollectCount(1);
		}

		return out;
	}

	@SuppressWarnings("unchecked")
	public static MatrixBlock toMatrixBlock(RDDObject rdd, int rlen, int clen, long nnz) {
		return toMatrixBlock(
				(JavaPairRDD<MatrixIndexes, MatrixCell>) rdd.getRDD(),
				rlen, clen, nnz);
	}

	/**
	 * Utility method for creating a single matrix block out of a binary cell RDD.
	 * Note that this collect call might trigger execution of any pending transformations.
	 *
	 * @param rdd JavaPairRDD for matrix block
	 * @param rlen number of rows
	 * @param clen number of columns
	 * @param nnz number of non-zeros
	 * @return matrix block
	 */
	public static MatrixBlock toMatrixBlock(JavaPairRDD<MatrixIndexes, MatrixCell> rdd, int rlen, int clen, long nnz)
	{
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		MatrixBlock out = null;

		//determine target sparse/dense representation
		long lnnz = (nnz >= 0) ? nnz : (long)rlen * clen;
		boolean sparse = MatrixBlock.evalSparseFormatInMemory(rlen, clen, lnnz);

		//create output matrix block (w/ lazy allocation)
		out = new MatrixBlock(rlen, clen, sparse);

		List<Tuple2<MatrixIndexes,MatrixCell>> list = rdd.collect();

		//copy blocks one-at-a-time into output matrix block
		for( Tuple2<MatrixIndexes,MatrixCell> keyval : list )
		{
			//unpack index-block pair
			MatrixIndexes ix = keyval._1();
			MatrixCell cell = keyval._2();

			//append cell to dense/sparse target in order to avoid shifting for sparse
			//note: this append requires a final sort of sparse rows
			out.appendValue((int)ix.getRowIndex()-1, (int)ix.getColumnIndex()-1, cell.getValue());
		}

		//post-processing output matrix
		if( sparse )
			out.sortSparseRows();
		out.recomputeNonZeros();
		out.examSparsity();

		if (DMLScript.STATISTICS) {
			Statistics.accSparkCollectTime(System.nanoTime() - t0);
			Statistics.incSparkCollectCount(1);
		}

		return out;
	}

	public static PartitionedBlock<MatrixBlock> toPartitionedMatrixBlock(JavaPairRDD<MatrixIndexes,MatrixBlock> rdd, int rlen, int clen, int brlen, int bclen, long nnz)
	{

		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		PartitionedBlock<MatrixBlock> out = new PartitionedBlock<>(rlen, clen, brlen, bclen);
		List<Tuple2<MatrixIndexes,MatrixBlock>> list = rdd.collect();

		//copy blocks one-at-a-time into output matrix block
		for( Tuple2<MatrixIndexes,MatrixBlock> keyval : list )
		{
			//unpack index-block pair
			MatrixIndexes ix = keyval._1();
			MatrixBlock block = keyval._2();
			out.setBlock((int)ix.getRowIndex(), (int)ix.getColumnIndex(), block);
		}

		if (DMLScript.STATISTICS) {
			Statistics.accSparkCollectTime(System.nanoTime() - t0);
			Statistics.incSparkCollectCount(1);
		}

		return out;
	}

	@SuppressWarnings("unchecked")
	public static FrameBlock toFrameBlock(RDDObject rdd, ValueType[] schema, int rlen, int clen) {
		JavaPairRDD<Long,FrameBlock> lrdd = (JavaPairRDD<Long,FrameBlock>) rdd.getRDD();
		return toFrameBlock(lrdd, schema, rlen, clen);
	}

	public static FrameBlock toFrameBlock(JavaPairRDD<Long,FrameBlock> rdd, ValueType[] schema, int rlen, int clen) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

		if(schema == null)
			schema = UtilFunctions.nCopies(clen, ValueType.STRING);

		//create output frame block (w/ lazy allocation)
		FrameBlock out = new FrameBlock(schema);
		out.ensureAllocatedColumns(rlen);

		List<Tuple2<Long,FrameBlock>> list = rdd.collect();

		//copy blocks one-at-a-time into output matrix block
		for( Tuple2<Long,FrameBlock> keyval : list )
		{
			//unpack index-block pair
			int ix = (int)(keyval._1() - 1);
			FrameBlock block = keyval._2();

			//copy into output frame
			out.copy( ix, ix+block.getNumRows()-1, 0, block.getNumColumns()-1, block );
			if( ix == 0 ) {
				out.setColumnNames(block.getColumnNames());
				out.setColumnMetadata(block.getColumnMetadata());
			}
		}

		if (DMLScript.STATISTICS) {
			Statistics.accSparkCollectTime(System.nanoTime() - t0);
			Statistics.incSparkCollectCount(1);
		}

		return out;
	}

	@SuppressWarnings("unchecked")
	public static long writeRDDtoHDFS( RDDObject rdd, String path, OutputInfo oinfo )
	{
		JavaPairRDD<MatrixIndexes,MatrixBlock> lrdd = (JavaPairRDD<MatrixIndexes, MatrixBlock>) rdd.getRDD();

		//piggyback nnz maintenance on write
		LongAccumulator aNnz = getSparkContextStatic().sc().longAccumulator("nnz");
		lrdd = lrdd.mapValues(new ComputeBinaryBlockNnzFunction(aNnz));

		//save file is an action which also triggers nnz maintenance
		lrdd.saveAsHadoopFile(path,
				oinfo.outputKeyClass,
				oinfo.outputValueClass,
				oinfo.outputFormatClass);

		//return nnz aggregate of all blocks
		return aNnz.value();
	}

	@SuppressWarnings("unchecked")
	public static void writeFrameRDDtoHDFS( RDDObject rdd, String path, OutputInfo oinfo )
	{
		JavaPairRDD<?, FrameBlock> lrdd = (JavaPairRDD<Long, FrameBlock>) rdd.getRDD();

		//convert keys to writables if necessary
		if( oinfo == OutputInfo.BinaryBlockOutputInfo ) {
			lrdd = ((JavaPairRDD<Long, FrameBlock>)lrdd).mapToPair(
					new LongFrameToLongWritableFrameFunction());
			oinfo = OutputInfo.BinaryBlockFrameOutputInfo;
		}

		//save file is an action which also triggers nnz maintenance
		lrdd.saveAsHadoopFile(path,
				oinfo.outputKeyClass,
				oinfo.outputValueClass,
				oinfo.outputFormatClass);
	}

	///////////////////////////////////////////
	// Cleanup of RDDs and Broadcast variables
	///////

	/**
	 * Adds a child rdd object to the lineage of a parent rdd.
	 *
	 * @param varParent parent variable
	 * @param varChild child variable
	 */
	public void addLineageRDD(String varParent, String varChild) {
		RDDObject parent = getCacheableData(varParent).getRDDHandle();
		RDDObject child = getCacheableData(varChild).getRDDHandle();
		parent.addLineageChild( child );
	}

	/**
	 * Adds a child broadcast object to the lineage of a parent rdd.
	 *
	 * @param varParent parent variable
	 * @param varChild child variable
	 */
	public void addLineageBroadcast(String varParent, String varChild) {
		RDDObject parent = getCacheableData(varParent).getRDDHandle();
		BroadcastObject<?> child = getCacheableData(varChild).getBroadcastHandle();
		parent.addLineageChild( child );
	}

	public void addLineage(String varParent, String varChild, boolean broadcast) {
		if( broadcast )
			addLineageBroadcast(varParent, varChild);
		else
			addLineageRDD(varParent, varChild);
	}

	@Override
	public void cleanupCacheableData(CacheableData<?> mo)
	{
		//NOTE: this method overwrites the default behavior of cleanupMatrixObject
		//and hence is transparently used by rmvar instructions and other users. The
		//core difference is the lineage-based cleanup of RDD and broadcast variables.

		if (DMLScript.JMLC_MEM_STATISTICS)
			Statistics.removeCPMemObject(System.identityHashCode(mo));

		if( !mo.isCleanupEnabled() )
			return;
		
		try
		{
			//compute ref count only if matrix cleanup actually necessary
			if( !getVariables().hasReferences(mo) ) {
				//clean cached data
				mo.clearData();

				//clean hdfs data if no pending rdd operations on it
				if( mo.isHDFSFileExists() && mo.getFileName()!=null ) {
					if( mo.getRDDHandle()==null ) {
						HDFSTool.deleteFileWithMTDIfExistOnHDFS(mo.getFileName());
					}
					else { //deferred file removal
						RDDObject rdd = mo.getRDDHandle();
						rdd.setHDFSFilename(mo.getFileName());
					}
				}

				//cleanup RDD and broadcast variables (recursive)
				//note: requires that mo.clearData already removed back references
				if( mo.getRDDHandle()!=null ) {
					rCleanupLineageObject(mo.getRDDHandle());
				}
				if( mo.getBroadcastHandle()!=null ) {
					rCleanupLineageObject(mo.getBroadcastHandle());
				}
			}
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void rCleanupLineageObject(LineageObject lob)
		throws IOException
	{
		//abort recursive cleanup if still consumers
		if( lob.getNumReferences() > 0 )
			return;

		//abort if still reachable through matrix object (via back references for
		//robustness in function calls and to prevent repeated scans of the symbol table)
		if( lob.hasBackReference() )
			return;

		//cleanup current lineage object (from driver/executors)
		//incl deferred hdfs file removal (only if metadata set by cleanup call)
		if( lob instanceof RDDObject ) {
			RDDObject rdd = (RDDObject)lob;
			int rddID = rdd.getRDD().id();
			cleanupRDDVariable(rdd.getRDD());
			if( rdd.getHDFSFilename()!=null ) { //deferred file removal
				HDFSTool.deleteFileWithMTDIfExistOnHDFS(rdd.getHDFSFilename());
			}
			if( rdd.isParallelizedRDD() )
				_parRDDs.deregisterRDD(rddID);
		}
		else if( lob instanceof BroadcastObject ) {
			BroadcastObject bob = (BroadcastObject) lob;
			// clean the partitioned broadcast
			if (bob.isPartitionedBroadcastValid()) {
				PartitionedBroadcast pbm = bob.getPartitionedBroadcast();
				if( pbm != null ) //robustness evictions
					pbm.destroy();
			}
			// clean the non-partitioned broadcast
			if (((BroadcastObject) lob).isNonPartitionedBroadcastValid()) {
				Broadcast<CacheableData> bc = bob.getNonPartitionedBroadcast();
				if( bc != null ) //robustness evictions
					cleanupBroadcastVariable(bc);
			}
			CacheableData.addBroadcastSize(-bob.getNonPartitionedBroadcastSize());
		}

		//recursively process lineage children
		for( LineageObject c : lob.getLineageChilds() ){
			c.decrementNumReferences();
			rCleanupLineageObject(c);
		}
	}

	/**
	 * This call destroys a broadcast variable at all executors and the driver.
	 * Hence, it is intended to be used on rmvar only. Depending on the
	 * ASYNCHRONOUS_VAR_DESTROY configuration, this is asynchronous or not.
	 *
	 * @param bvar broadcast variable
	 */
	public static void cleanupBroadcastVariable(Broadcast<?> bvar)
	{
		//In comparison to 'unpersist' (which would only delete the broadcast
		//from the executors), this call also deletes related data from the driver.
		if( bvar.isValid() ) {
			bvar.destroy( !ASYNCHRONOUS_VAR_DESTROY );
		}
	}

	/**
	 * This call removes an rdd variable from executor memory and disk if required.
	 * Hence, it is intended to be used on rmvar only. Depending on the
	 * ASYNCHRONOUS_VAR_DESTROY configuration, this is asynchronous or not.
	 *
	 * @param rvar rdd variable to remove
	 */
	public static void cleanupRDDVariable(JavaPairRDD<?,?> rvar)
	{
		if( rvar.getStorageLevel()!=StorageLevel.NONE() ) {
			rvar.unpersist( !ASYNCHRONOUS_VAR_DESTROY );
		}
	}

	@SuppressWarnings("unchecked")
	public void repartitionAndCacheMatrixObject( String var ) {
		MatrixObject mo = getMatrixObject(var);
		MatrixCharacteristics mcIn = mo.getMatrixCharacteristics();

		//double check size to avoid unnecessary spark context creation
		if( !OptimizerUtils.exceedsCachingThreshold(mo.getNumColumns(), (double)
				OptimizerUtils.estimateSizeExactSparsity(mcIn)) )
			return;

		//get input rdd and default storage level
		JavaPairRDD<MatrixIndexes,MatrixBlock> in = (JavaPairRDD<MatrixIndexes, MatrixBlock>)
				getRDDHandleForMatrixObject(mo, InputInfo.BinaryBlockInputInfo);

		//avoid unnecessary caching of input in order to reduce memory pressure
		if( mo.getRDDHandle().allowsShortCircuitRead()
			&& isRDDMarkedForCaching(in.id()) && !isRDDCached(in.id()) ) {
			in = (JavaPairRDD<MatrixIndexes,MatrixBlock>)
					((RDDObject)mo.getRDDHandle().getLineageChilds().get(0)).getRDD();

			//investigate issue of unnecessarily large number of partitions
			int numPartitions = SparkUtils.getNumPreferredPartitions(mcIn, in);
			if( numPartitions < in.getNumPartitions() )
				in = in.coalesce( numPartitions );
		}

		//repartition rdd (force creation of shuffled rdd via merge), note: without deep copy albeit
		//executed on the original data, because there will be no merge, i.e., no key duplicates
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = RDDAggregateUtils.mergeByKey(in, false);

		//convert mcsr into memory-efficient csr if potentially sparse
		if( OptimizerUtils.checkSparseBlockCSRConversion(mcIn) ) {
			out = out.mapValues(new CreateSparseBlockFunction(SparseBlock.Type.CSR));
		}

		//persist rdd in default storage level
		out.persist( Checkpoint.DEFAULT_STORAGE_LEVEL )
		   .count(); //trigger caching to prevent contention

		//create new rdd handle, in-place of current matrix object
		RDDObject inro =  mo.getRDDHandle();  //guaranteed to exist (see above)
		RDDObject outro = new RDDObject(out); //create new rdd object
		outro.setCheckpointRDD(true);         //mark as checkpointed
		outro.addLineageChild(inro);          //keep lineage to prevent cycles on cleanup
		mo.setRDDHandle(outro);
	}

	@SuppressWarnings("unchecked")
	public void cacheMatrixObject( String var ) {
		//get input rdd and default storage level
		MatrixObject mo = getMatrixObject(var);

		//double check size to avoid unnecessary spark context creation
		if( !OptimizerUtils.exceedsCachingThreshold(mo.getNumColumns(), (double)
				OptimizerUtils.estimateSizeExactSparsity(mo.getMatrixCharacteristics())) )
			return;

		JavaPairRDD<MatrixIndexes,MatrixBlock> in = (JavaPairRDD<MatrixIndexes, MatrixBlock>)
				getRDDHandleForMatrixObject(mo, InputInfo.BinaryBlockInputInfo);

		//persist rdd (force rdd caching, if not already cached)
		if( !isRDDCached(in.id()) )
			in.count(); //trigger caching to prevent contention
	}

	public int setThreadLocalSchedulerPool() {
		int pool = -1;
		if( FAIR_SCHEDULER_MODE ) {
			pool = allocSchedulerPoolName();
			getSparkContext().sc().setLocalProperty(
				"spark.scheduler.pool", "parforPool"+pool);
		}
		return pool;
	}

	public void cleanupThreadLocalSchedulerPool(int pool) {
		if( FAIR_SCHEDULER_MODE ) {
			freeSchedulerPoolName(pool);
			getSparkContext().sc().setLocalProperty(
				"spark.scheduler.pool", null);
		}
	}
	
	private static synchronized int allocSchedulerPoolName() {
		int pool = ArrayUtils.indexOf(_poolBuff, false);
		//grow pool on demand
		if( pool < 0 ) {
			pool = _poolBuff.length;
			_poolBuff = Arrays.copyOf(_poolBuff,
				(int)Math.min(2L*pool, Integer.MAX_VALUE));
		}
		//mark pool name for in use
		_poolBuff[pool] = true;
		return pool;
	}
	
	private static synchronized void freeSchedulerPoolName(int pool) {
		_poolBuff[pool] = false;
	}

	private boolean isRDDMarkedForCaching( int rddID ) {
		JavaSparkContext jsc = getSparkContext();
		return jsc.sc().getPersistentRDDs().contains(rddID);
	}

	public boolean isRDDCached( int rddID ) {
		//check that rdd is marked for caching
		JavaSparkContext jsc = getSparkContext();
		if( !jsc.sc().getPersistentRDDs().contains(rddID) ) {
			return false;
		}

		//check that rdd is actually already cached
		for( RDDInfo info : jsc.sc().getRDDStorageInfo() ) {
			if( info.id() == rddID )
				return info.isCached();
		}
		return false;
	}

	///////////////////////////////////////////
	// Spark configuration handling
	///////

	/**
	 * Obtains the lazily analyzed spark cluster configuration.
	 *
	 * @return spark cluster configuration
	 */
	public static SparkClusterConfig getSparkClusterConfig() {
		//lazy creation of spark cluster config
		if( _sconf == null )
			_sconf = new SparkClusterConfig();
		return _sconf;
	}

	/**
	 * Obtains the available memory budget for broadcast variables in bytes.
	 *
	 * @return broadcast memory budget
	 */
	public static double getBroadcastMemoryBudget() {
		return getSparkClusterConfig()
			.getBroadcastMemoryBudget();
	}

	/**
	 * Obtain the available memory budget for data storage in bytes.
	 *
	 * @param min      flag for minimum data budget
	 * @param refresh  flag for refresh with spark context
	 * @return data memory budget
	 */
	public static double getDataMemoryBudget(boolean min, boolean refresh) {
		return getSparkClusterConfig()
			.getDataMemoryBudget(min, refresh);
	}

	/**
	 * Obtain the number of executors in the cluster (excluding the driver).
	 *
	 * @return number of executors
	 */
	public static int getNumExecutors() {
		return getSparkClusterConfig()
			.getNumExecutors();
	}

	/**
	 * Obtain the default degree of parallelism (cores in the cluster).
	 *
	 * @param refresh  flag for refresh with spark context
	 * @return default degree of parallelism
	 */
	public static int getDefaultParallelism(boolean refresh) {
		return getSparkClusterConfig()
			.getDefaultParallelism(refresh);
	}

	/**
	 * Captures relevant spark cluster configuration properties, e.g., memory budgets and
	 * degree of parallelism. This configuration abstracts legacy (< Spark 1.6) and current
	 * configurations and provides a unified view.
	 */
	public static class SparkClusterConfig
	{
		//broadcasts are stored in mem-and-disk in data space, this config
		//defines the fraction of data space to be used as broadcast budget
		private static final double BROADCAST_DATA_FRACTION = 0.35;

		//forward private config from Spark's UnifiedMemoryManager.scala (>1.6)
		private static final long RESERVED_SYSTEM_MEMORY_BYTES = 300 * 1024 * 1024;

		//meta configurations
		private boolean _legacyVersion = false; //spark version <1.6
		private boolean _confOnly = false; //infrastructure info based on config

		//memory management configurations
		private long _memExecutor = -1; //mem per executor
		private double _memDataMinFrac = -1; //minimum data fraction
		private double _memDataMaxFrac = -1; //maximum data fraction
		private double _memBroadcastFrac = -1; //broadcast fraction

		//degree of parallelism configurations
		private int _numExecutors = -1; //total executors
		private int _defaultPar = -1; //total vcores

		public SparkClusterConfig()
		{
			SparkConf sconf = createSystemMLSparkConf();
			_confOnly = true;

			//parse version and config
			String sparkVersion = getSparkVersionString();
			_legacyVersion = (UtilFunctions.compareVersion(sparkVersion, "1.6.0") < 0
					|| sconf.getBoolean("spark.memory.useLegacyMode", false) );

			//obtain basic spark configurations
			if( _legacyVersion )
				analyzeSparkConfiguationLegacy(sconf);
			else
				analyzeSparkConfiguation(sconf);

			//log debug of created spark cluster config
			if( LOG.isDebugEnabled() )
				LOG.debug( this.toString() );
		}

		public long getBroadcastMemoryBudget() {
			return (long) (_memExecutor * _memBroadcastFrac);
		}

		public long getDataMemoryBudget(boolean min, boolean refresh) {
			//always get the current num executors on refresh because this might
			//change if not all executors are initially allocated and it is plan-relevant
			int numExec = _numExecutors;
			if( (refresh && !_confOnly) || isSparkContextCreated() ) {
				JavaSparkContext jsc = getSparkContextStatic();
				numExec = Math.max(jsc.sc().getExecutorMemoryStatus().size() - 1, 1);
			}

			//compute data memory budget
			return (long) ( numExec * _memExecutor *
				(min ? _memDataMinFrac : _memDataMaxFrac) );
		}

		public int getNumExecutors() {
			if( _numExecutors < 0 )
				analyzeSparkParallelismConfiguation(null);
			return _numExecutors;
		}

		public int getDefaultParallelism(boolean refresh) {
			if( _defaultPar < 0 && !refresh )
				analyzeSparkParallelismConfiguation(null);

			//always get the current default parallelism on refresh because this might
			//change if not all executors are initially allocated and it is plan-relevant
			int par = ( (refresh && !_confOnly) || isSparkContextCreated() ) ?
				getSparkContextStatic().defaultParallelism() : _defaultPar;
			return Math.max(par, 1); //robustness min parallelism
		}

		public void analyzeSparkConfiguationLegacy(SparkConf conf)  {
			//ensure allocated spark conf
			SparkConf sconf = (conf == null) ? createSystemMLSparkConf() : conf;
			
			//parse absolute executor memory
			_memExecutor = UtilFunctions.parseMemorySize(
					sconf.get("spark.executor.memory", "1g"));

			//get data and shuffle memory ratios (defaults not specified in job conf)
			double dataFrac = sconf.getDouble("spark.storage.memoryFraction", 0.6); //default 60%
			_memDataMinFrac = dataFrac;
			_memDataMaxFrac = dataFrac;
			_memBroadcastFrac = dataFrac * BROADCAST_DATA_FRACTION; //default 18%

			//analyze spark degree of parallelism
			analyzeSparkParallelismConfiguation(sconf);
		}

		public void analyzeSparkConfiguation(SparkConf conf) {
			//ensure allocated spark conf
			SparkConf sconf = (conf == null) ? createSystemMLSparkConf() : conf;
			
			//parse absolute executor memory, incl fixed cut off
			_memExecutor = UtilFunctions.parseMemorySize(
					sconf.get("spark.executor.memory", "1g"))
					- RESERVED_SYSTEM_MEMORY_BYTES;

			//get data and shuffle memory ratios (defaults not specified in job conf)
			_memDataMinFrac = sconf.getDouble("spark.memory.storageFraction", 0.5); //default 50%
			_memDataMaxFrac = sconf.getDouble("spark.memory.fraction", 0.6); //default 60%
			_memBroadcastFrac = _memDataMaxFrac * BROADCAST_DATA_FRACTION; //default 21%
			
			//analyze spark degree of parallelism
			analyzeSparkParallelismConfiguation(sconf);
		}

		private void analyzeSparkParallelismConfiguation(SparkConf conf) {
			//ensure allocated spark conf
			SparkConf sconf = (conf == null) ? createSystemMLSparkConf() : conf;
			
			int numExecutors = sconf.getInt("spark.executor.instances", -1);
			int numCoresPerExec = sconf.getInt("spark.executor.cores", -1);
			int defaultPar = sconf.getInt("spark.default.parallelism", -1);

			if( numExecutors > 1 && (defaultPar > 1 || numCoresPerExec > 1) ) {
				_numExecutors = numExecutors;
				_defaultPar = (defaultPar>1) ? defaultPar : numExecutors * numCoresPerExec;
				_confOnly &= true;
			}
			else {
				//get default parallelism (total number of executors and cores)
				//note: spark context provides this information while conf does not
				//(for num executors we need to correct for driver and local mode)
				JavaSparkContext jsc = getSparkContextStatic();
				_numExecutors = Math.max(jsc.sc().getExecutorMemoryStatus().size() - 1, 1);
				_defaultPar = jsc.defaultParallelism();
				_confOnly &= false; //implies env info refresh w/ spark context
			}
		}

		/**
		 * Obtains the spark version string. If the spark context has been created,
		 * we simply get it from the context; otherwise, we use Spark internal
		 * constants to avoid creating the spark context just for the version.
		 *
		 * @return spark version string
		 */
		private static String getSparkVersionString() {
			//check for existing spark context
			if( isSparkContextCreated() )
				return getSparkContextStatic().version();

			//use spark internal constant to avoid context creation
			return org.apache.spark.package$.MODULE$.SPARK_VERSION();
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("SparkClusterConfig: \n");
			sb.append("-- legacyVersion    = " + _legacyVersion + " ("+getSparkVersionString()+")\n" );
			sb.append("-- confOnly         = " + _confOnly + "\n");
			sb.append("-- numExecutors     = " + _numExecutors + "\n");
			sb.append("-- defaultPar       = " + _defaultPar + "\n");
			sb.append("-- memExecutor      = " + _memExecutor + "\n");
			sb.append("-- memDataMinFrac   = " + _memDataMinFrac + "\n");
			sb.append("-- memDataMaxFrac   = " + _memDataMaxFrac + "\n");
			sb.append("-- memBroadcastFrac = " + _memBroadcastFrac + "\n");
			return sb.toString();
		}
	}

	private static class MemoryManagerParRDDs
	{
		private final long _limit;
		private long _size;
		private HashMap<Integer, Long> _rdds;

		public MemoryManagerParRDDs(double fractionMem) {
			_limit = (long)(fractionMem * InfrastructureAnalyzer.getLocalMaxMemory());
			_size = 0;
			_rdds = new HashMap<>();
		}

		public synchronized boolean reserve(long rddSize) {
			boolean ret = (rddSize + _size < _limit);
			_size += ret ? rddSize : 0;
			return ret;
		}

		public synchronized void registerRDD(int rddID, long rddSize, boolean reserved) {
			if( !reserved ) {
				throw new RuntimeException("Unsupported rdd registration "
						+ "without size reservation for "+rddSize+" bytes.");
			}
			_rdds.put(rddID, rddSize);
		}

		public synchronized void deregisterRDD(int rddID) {
			long rddSize = _rdds.remove(rddID);
			_size -= rddSize;
		}

		public synchronized void clear() {
			_size = 0;
			_rdds.clear();
		}
	}
}