package net.kieker.sourceinstrumentation.instrument;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;

import net.kieker.sourceinstrumentation.AllowedKiekerRecord;
import net.kieker.sourceinstrumentation.SamplingParameters;


public class BlockBuilder {

   private static final String BEFORE_OER_SOURCE = "      // collect data\n" +
         "      final boolean entrypoint;\n" +
         "      final String hostname = MonitoringController.getInstance().getHostname();\n" +
         "      final String sessionId = SessionRegistry.INSTANCE.recallThreadLocalSessionId();\n" +
         "      final int eoi; // this is executionOrderIndex-th execution in this trace\n" +
         "      final int ess; // this is the height in the dynamic call tree of this execution\n" +
         "      long traceId = ControlFlowRegistry.INSTANCE.recallThreadLocalTraceId(); // traceId, -1 if entry point\n" +
         "      if (traceId == -1) {\n" +
         "         entrypoint = true;\n" +
         "         traceId = ControlFlowRegistry.INSTANCE.getAndStoreUniqueThreadLocalTraceId();\n" +
         "         ControlFlowRegistry.INSTANCE.storeThreadLocalEOI(0);\n" +
         "         ControlFlowRegistry.INSTANCE.storeThreadLocalESS(1); // next operation is ess + 1\n" +
         "         eoi = 0;\n" +
         "         ess = 0;\n" +
         "      } else {\n" +
         "         entrypoint = false;\n" +
         "         eoi = ControlFlowRegistry.INSTANCE.incrementAndRecallThreadLocalEOI(); // ess > 1\n" +
         "         ess = ControlFlowRegistry.INSTANCE.recallAndIncrementThreadLocalESS(); // ess >= 0\n" +
         "         if ((eoi == -1) || (ess == -1)) {\n" +
         "            System.err.println(\"eoi and/or ess have invalid values: eoi == {} ess == {}\"+ eoi+ \"\" + ess);\n" +
         "            MonitoringController.getInstance().terminateMonitoring();\n" +
         "         }\n" +
         "      }\n" +
         "      // measure before\n" +
         "      final long tin = MonitoringController.getInstance().getTimeSource().getTime();\n";
   private static final String AFTER_OER_SOURCE = "// measure after\n" +
         "         final long tout = MonitoringController.getInstance().getTimeSource().getTime();\n" +
         "         MonitoringController.getInstance().newMonitoringRecord(new OperationExecutionRecord(signature, sessionId, traceId, tin, tout, hostname, eoi, ess));\n" +
         "         // cleanup\n" +
         "         if (entrypoint) {\n" +
         "            ControlFlowRegistry.INSTANCE.unsetThreadLocalTraceId();\n" +
         "            ControlFlowRegistry.INSTANCE.unsetThreadLocalEOI();\n" +
         "            ControlFlowRegistry.INSTANCE.unsetThreadLocalESS();\n" +
         "         } else {\n" +
         "            ControlFlowRegistry.INSTANCE.storeThreadLocalESS(ess); // next operation is ess\n" +
         "         }";

   private static final Logger LOG = LogManager.getLogger(BlockBuilder.class);

   private final AllowedKiekerRecord recordType;
   private final boolean enableDeactivation;

   public BlockBuilder(final AllowedKiekerRecord recordType, final boolean enableDeactivation) {
      this.recordType = recordType;
      this.enableDeactivation = enableDeactivation;
   }

   public BlockStmt buildConstructorStatement(final BlockStmt originalBlock, final String signature, final boolean addReturn) {
      LOG.debug("Statements: " + originalBlock.getStatements().size() + " " + signature);
      BlockStmt replacedStatement = new BlockStmt();
      ExplicitConstructorInvocationStmt constructorStatement = null;
      for (Statement st : originalBlock.getStatements()) {
         if (st instanceof ExplicitConstructorInvocationStmt) {
            constructorStatement = (ExplicitConstructorInvocationStmt) st;
         }
      }
      if (constructorStatement != null) {
         replacedStatement.addAndGetStatement(constructorStatement);
         originalBlock.getStatements().remove(constructorStatement);
      }

      final BlockStmt regularChangedStatement = buildStatement(originalBlock, signature, addReturn);
      for (Statement st : regularChangedStatement.getStatements()) {
         replacedStatement.addAndGetStatement(st);
      }

      return replacedStatement;
   }

   public BlockStmt buildSampleStatement(BlockStmt originalBlock, String signature, boolean addReturn, SamplingParameters parameters) {
      if (recordType.equals(AllowedKiekerRecord.OPERATIONEXECUTION)) {
         throw new RuntimeException("Not implemented yet (does Sampling + OperationExecutionRecord make sense?)");
      } else if (recordType.equals(AllowedKiekerRecord.REDUCED_OPERATIONEXECUTION)) {
         return buildSelectiveSamplingStatement(originalBlock, signature, addReturn, parameters);
      } else {
         throw new RuntimeException();
      }
   }

   public BlockStmt buildStatement(BlockStmt originalBlock, String signature, boolean addReturn) {
      if (recordType.equals(AllowedKiekerRecord.OPERATIONEXECUTION)) {
         return buildOperationExecutionStatement(originalBlock, signature, addReturn);
      } else if (recordType.equals(AllowedKiekerRecord.REDUCED_OPERATIONEXECUTION)) {
         return buildReducedOperationExecutionStatement(originalBlock, signature, addReturn);
      } else {
         throw new RuntimeException();
      }
   }

   public BlockStmt buildSelectiveSamplingStatement(BlockStmt originalBlock, String signature, boolean addReturn, SamplingParameters parameters) {
      BlockStmt replacedStatement = new BlockStmt();
      replacedStatement.addAndGetStatement("      final long tin = MonitoringController.getInstance().getTimeSource().getTime();");

      int count = 1000;

      BlockStmt finallyBlock = new BlockStmt();
      finallyBlock.addAndGetStatement(parameters.getFinalBlock(signature, count));
      TryStmt stmt = new TryStmt(originalBlock, new NodeList<>(), finallyBlock);
      replacedStatement.addAndGetStatement(stmt);

      return replacedStatement;
   }

   public BlockStmt buildReducedOperationExecutionStatement(BlockStmt originalBlock, String signature, boolean addReturn) {
      BlockStmt replacedStatement = new BlockStmt();

      buildHeader(originalBlock, signature, addReturn, replacedStatement);
      replacedStatement.addAndGetStatement("      final long tin = MonitoringController.getInstance().getTimeSource().getTime();");

      BlockStmt finallyBlock = new BlockStmt();
      finallyBlock.addAndGetStatement("// measure after\n");
      finallyBlock.addAndGetStatement("final long tout = MonitoringController.getInstance().getTimeSource().getTime()");
      finallyBlock.addAndGetStatement("MonitoringController.getInstance().newMonitoringRecord(new ReducedOperationExecutionRecord(signature, tin, tout))");
      TryStmt stmt = new TryStmt(originalBlock, new NodeList<>(), finallyBlock);
      replacedStatement.addAndGetStatement(stmt);
      return replacedStatement;
   }

   public BlockStmt buildOperationExecutionStatement(BlockStmt originalBlock, String signature, boolean addReturn) {
      BlockStmt replacedStatement = new BlockStmt();

      buildHeader(originalBlock, signature, addReturn, replacedStatement);
      replacedStatement.addAndGetStatement(BEFORE_OER_SOURCE);
      BlockStmt finallyBlock = new BlockStmt();
      finallyBlock.addAndGetStatement(AFTER_OER_SOURCE);
      TryStmt stmt = new TryStmt(originalBlock, new NodeList<>(), finallyBlock);
      replacedStatement.addAndGetStatement(stmt);
      return replacedStatement;
   }

   private void buildHeader(BlockStmt originalBlock, String signature, boolean addReturn, BlockStmt replacedStatement) {
      if (enableDeactivation) {
         replacedStatement.addAndGetStatement("if (!MonitoringController.getInstance().isMonitoringEnabled()) {\n" +
               originalBlock.toString() +
               (addReturn ? "return;" : "") +
               "      }");
      }
      replacedStatement.addAndGetStatement("final String signature = \"" + signature + "\";");
      if (enableDeactivation) {
         replacedStatement.addAndGetStatement("if (!MonitoringController.getInstance().isProbeActivated(signature)) {\n" +
               originalBlock.toString() +
               (addReturn ? "return;" : "") +
               "      }");
      }
   }

   public BlockStmt buildEmptyConstructor(String signature) {
      BlockStmt replacedStatement = new BlockStmt();
      if (recordType.equals(AllowedKiekerRecord.OPERATIONEXECUTION)) {
         buildOperationExecutionRecordDefaultConstructor(signature, replacedStatement);
      } else if (recordType.equals(AllowedKiekerRecord.REDUCED_OPERATIONEXECUTION)) {
         buildReducedOperationExecutionRecordDefaultConstructor(signature, replacedStatement);
      } else {
         throw new RuntimeException();
      }
      return replacedStatement;
   }
   
   public BlockStmt buildEmptySamplingConstructor(String signature, SamplingParameters parameters) {
      BlockStmt replacedStatement = new BlockStmt();
      if (recordType.equals(AllowedKiekerRecord.OPERATIONEXECUTION)) {
         throw new RuntimeException("Not implemented yet (does Sampling + OperationExecutionRecord make sense?)");
      } else if (recordType.equals(AllowedKiekerRecord.REDUCED_OPERATIONEXECUTION)) {
         buildHeader(replacedStatement, signature, false, replacedStatement);
         replacedStatement.addAndGetStatement("      final long tin = MonitoringController.getInstance().getTimeSource().getTime();");
         replacedStatement.addAndGetStatement(parameters.getFinalBlock(signature, 1000));
      } else {
         throw new RuntimeException();
      }
      return replacedStatement;
   }

   private void buildReducedOperationExecutionRecordDefaultConstructor(String signature, BlockStmt replacedStatement) {
      buildHeader(replacedStatement, signature, false, replacedStatement);
      replacedStatement.addAndGetStatement("      final long tin = MonitoringController.getInstance().getTimeSource().getTime();");
      replacedStatement.addAndGetStatement("// measure after\n");
      replacedStatement.addAndGetStatement("final long tout = MonitoringController.getInstance().getTimeSource().getTime()");
      replacedStatement.addAndGetStatement("MonitoringController.getInstance().newMonitoringRecord(new ReducedOperationExecutionRecord(signature, tin, tout))");
   }

   private void buildOperationExecutionRecordDefaultConstructor(String signature, BlockStmt replacedStatement) {
      buildHeader(new BlockStmt(), signature, false, replacedStatement);
      replacedStatement.addAndGetStatement(BEFORE_OER_SOURCE + AFTER_OER_SOURCE);
   }
}
