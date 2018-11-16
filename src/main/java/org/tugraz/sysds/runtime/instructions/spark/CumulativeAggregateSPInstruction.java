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

package org.tugraz.sysds.runtime.instructions.spark;


import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.PairFunction;
import org.tugraz.sysds.runtime.controlprogram.context.ExecutionContext;
import org.tugraz.sysds.runtime.controlprogram.context.SparkExecutionContext;
import org.tugraz.sysds.runtime.functionobjects.Builtin;
import org.tugraz.sysds.runtime.functionobjects.PlusMultiply;
import org.tugraz.sysds.runtime.instructions.InstructionUtils;
import org.tugraz.sysds.runtime.instructions.cp.CPOperand;
import org.tugraz.sysds.runtime.instructions.spark.utils.RDDAggregateUtils;
import org.tugraz.sysds.runtime.matrix.data.MatrixBlock;
import org.tugraz.sysds.runtime.matrix.data.MatrixIndexes;
import org.tugraz.sysds.runtime.matrix.data.OperationsOnMatrixValues;
import org.tugraz.sysds.runtime.matrix.operators.AggregateUnaryOperator;
import org.tugraz.sysds.runtime.matrix.operators.UnaryOperator;
import org.tugraz.sysds.runtime.meta.MatrixCharacteristics;

import scala.Tuple2;

public class CumulativeAggregateSPInstruction extends AggregateUnarySPInstruction {

	private CumulativeAggregateSPInstruction(AggregateUnaryOperator op, CPOperand in1, CPOperand out, String opcode, String istr) {
		super(SPType.CumsumAggregate, op, null, in1, out, null, opcode, istr);
	}

	public static CumulativeAggregateSPInstruction parseInstruction( String str ) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType( str );
		InstructionUtils.checkNumFields ( parts, 2 );
		String opcode = parts[0];
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand out = new CPOperand(parts[2]);
		AggregateUnaryOperator aggun = InstructionUtils.parseCumulativeAggregateUnaryOperator(opcode);
		return new CumulativeAggregateSPInstruction(aggun, in1, out, opcode, str);	
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) {
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		MatrixCharacteristics mc = sec.getMatrixCharacteristics(input1.getName());
		long rlen = mc.getRows();
		int brlen = mc.getRowsPerBlock();
		int bclen = mc.getColsPerBlock();
		
		//get input
		JavaPairRDD<MatrixIndexes,MatrixBlock> in = sec.getBinaryBlockRDDHandleForVariable( input1.getName() );
		
		//execute unary aggregate (w/ implicit drop correction)
		AggregateUnaryOperator auop = (AggregateUnaryOperator) _optr;
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = 
				in.mapToPair(new RDDCumAggFunction(auop, rlen, brlen, bclen));
		out = RDDAggregateUtils.mergeByKey(out, false);
		
		//put output handle in symbol table
		sec.setRDDHandleForVariable(output.getName(), out);	
		sec.addLineageRDD(output.getName(), input1.getName());
	}

	private static class RDDCumAggFunction implements PairFunction<Tuple2<MatrixIndexes, MatrixBlock>, MatrixIndexes, MatrixBlock> 
	{
		private static final long serialVersionUID = 11324676268945117L;
		
		private final AggregateUnaryOperator _op;
		private UnaryOperator _uop = null;
		private final long _rlen;
		private final int _brlen;
		private final int _bclen;
		
		public RDDCumAggFunction( AggregateUnaryOperator op, long rlen, int brlen, int bclen ) {
			_op = op;
			_rlen = rlen;
			_brlen = brlen;
			_bclen = bclen;
		}
		
		@Override
		public Tuple2<MatrixIndexes, MatrixBlock> call( Tuple2<MatrixIndexes, MatrixBlock> arg0 ) 
			throws Exception 
		{
			MatrixIndexes ixIn = arg0._1();
			MatrixBlock blkIn = arg0._2();

			MatrixIndexes ixOut = new MatrixIndexes();
			MatrixBlock blkOut = new MatrixBlock();
			
			//process instruction
			AggregateUnaryOperator aop = (AggregateUnaryOperator)_op;
			if( aop.aggOp.increOp.fn instanceof PlusMultiply ) { //cumsumprod
				aop.indexFn.execute(ixIn, ixOut);
				if( _uop == null )
					_uop = new UnaryOperator(Builtin.getBuiltinFnObject("ucumk+*"));
				MatrixBlock t1 = (MatrixBlock) blkIn.unaryOperations(_uop, new MatrixBlock());
				MatrixBlock t2 = blkIn.slice(0, blkIn.getNumRows()-1, 1, 1, new MatrixBlock());
				blkOut.reset(1, 2);
				blkOut.quickSetValue(0, 0, t1.quickGetValue(t1.getNumRows()-1, 0));
				blkOut.quickSetValue(0, 1, t2.prod());
			}
			else { //general case
				OperationsOnMatrixValues.performAggregateUnary( ixIn, blkIn, ixOut, blkOut, aop, _brlen, _bclen);
				if( aop.aggOp.correctionExists )
					blkOut.dropLastRowsOrColumns(aop.aggOp.correctionLocation);
			}
			
			//cumsum expand partial aggregates
			long rlenOut = (long)Math.ceil((double)_rlen/_brlen);
			long rixOut = (long)Math.ceil((double)ixIn.getRowIndex()/_brlen);
			int rlenBlk = (int) Math.min(rlenOut-(rixOut-1)*_brlen, _brlen);
			int clenBlk = blkOut.getNumColumns();
			int posBlk = (int) ((ixIn.getRowIndex()-1) % _brlen);
			MatrixBlock blkOut2 = new MatrixBlock(rlenBlk, clenBlk, false);
			blkOut2.copy(posBlk, posBlk, 0, clenBlk-1, blkOut, true);
			ixOut.setIndexes(rixOut, ixOut.getColumnIndex());
			
			//output new tuple
			return new Tuple2<>(ixOut, blkOut2);
		}
	}
}