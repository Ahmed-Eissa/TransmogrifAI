/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package com.salesforce.op


import com.salesforce.op.DAG._
import com.salesforce.op.features.FeatureLike
import com.salesforce.op.features.types._
import com.salesforce.op.stages.impl.classification.{BinaryClassificationModelSelector, OpLogisticRegression}
import com.salesforce.op.stages.impl.feature.{OpLDA, OpScalarStandardScaler}
import com.salesforce.op.stages.impl.preparators.SanityChecker
import com.salesforce.op.stages.impl.selector.ModelSelectorBase
import com.salesforce.op.test.{TestFeatureBuilder, TestSparkContext}
import com.salesforce.op.testkit.{RandomBinary, RandomReal, RandomVector}
import org.apache.spark.ml.{Estimator, Model}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FlatSpec


@RunWith(classOf[JUnitRunner])
class OpWorkflowCoreTest extends FlatSpec with TestSparkContext {
  // Types
  type MS = ModelSelectorBase[_ <: Model[_], _ <: Estimator[_]]

  // Random Data
  val count = 1000
  val sizeOfVector = 100
  val seed = 1223L
  val p = 0.3
  val vectors = RandomVector.dense(RandomReal.uniform[Real](-1.0, 1.0), sizeOfVector).take(count)
  val response = RandomBinary(p).withProbabilityOfEmpty(0.0).take(count).map(_.toDouble.toRealNN(0))
  val response2 = RandomBinary(p).withProbabilityOfEmpty(0.0).take(count).map(_.toDouble.toRealNN(0))
  val (data, rawLabel, rawLabel2, features) = TestFeatureBuilder[RealNN, RealNN, OPVector]("label", "label2",
    "features", response.zip(response2).zip(vectors).map(v => (v._1._1, v._1._2, v._2)).toSeq)
  val label = rawLabel.copy(isResponse = true)
  val label2 = rawLabel2.copy(isResponse = true)

  // LDA (nonCVTS Stage)
  val lda = new OpLDA()

  // Sanity Checker (cVTS Stage)
  val sanityChecker = new SanityChecker()

  // Workflow
  val wf = new OpWorkflow()


  Spec[OpWorkflowCore] should "handle empty DAG" in {
    assert(
      res = cutDAG(wf),
      modelSelector = None,
      nonCVTSDAG = Array.empty[Layer],
      cVTSDAG = Array.empty[Layer]
    )
  }

  it should "cut simple DAG containing modelSelector only" in {
    val ms = BinaryClassificationModelSelector()
    val (pred, _, _) = ms.setInput(label, features).getOutput()

    assert(
      res = cutDAG(wf.setResultFeatures(pred)),
      modelSelector = Option(ms.stage1),
      nonCVTSDAG = Array.empty[Layer],
      cVTSDAG = Array.empty[Layer]
    )
  }

  it should "cut simple DAG with nonCVTS and cVTS stage" in {
    val ldaFeatures = lda.setInput(features).getOutput()
    val checkedFeatures = sanityChecker.setInput(label, ldaFeatures).getOutput()
    val ms = BinaryClassificationModelSelector()
    val (pred, _, _) = ms.setInput(label, checkedFeatures).getOutput()

    assert(
      res = cutDAG(wf.setResultFeatures(pred)),
      modelSelector = Option(ms.stage1),
      nonCVTSDAG = Array(Array((lda, 2))),
      cVTSDAG = Array(Array((sanityChecker, 1)))
    )
  }

  it should "cut DAG with no nonCVTS stage" in {
    val checkedFeatures = sanityChecker.setInput(label, features).getOutput()
    val ms = BinaryClassificationModelSelector()
    val (pred, _, _) = ms.setInput(label, checkedFeatures).getOutput()

    assert(
      res = cutDAG(wf.setResultFeatures(pred)),
      modelSelector = Option(ms.stage1),
      nonCVTSDAG = Array.empty[Layer],
      cVTSDAG = Array(Array((sanityChecker, 1)))
    )
  }

  it should "cut DAG with no cVTS stage before ModelSelector" in {
    val ms = BinaryClassificationModelSelector()
    val ldaFeatures = lda.setInput(features).getOutput()
    val (pred, _, _) = ms.setInput(label, ldaFeatures).getOutput()

    assert(
      res = cutDAG(wf.setResultFeatures(pred)),
      modelSelector = Option(ms.stage1),
      nonCVTSDAG = Array(Array((lda, 1))),
      cVTSDAG = Array.empty[Layer]
    )
  }

  it should "cut DAG with no ModelSelector" in {
    val ldaFeatures = lda.setInput(features).getOutput()
    val checkedFeatures = sanityChecker.setInput(label, ldaFeatures).getOutput()

    assert(
      res = cutDAG(wf.setResultFeatures(checkedFeatures)),
      modelSelector = None,
      nonCVTSDAG = Array.empty[Layer],
      cVTSDAG = Array.empty[Layer]
    )
  }

  it should "throw an error when there is more than one ModelSelector in parallel" in {
    val ms1 = BinaryClassificationModelSelector()
    val ms2 = BinaryClassificationModelSelector()
    val (pred1, _, _) = ms1.setInput(label, features).getOutput()
    val (pred2, _, _) = ms2.setInput(label2, features).getOutput()

    val error = intercept[IllegalArgumentException](cutDAG(wf.setResultFeatures(pred1, pred2)))
    error.getMessage
      .contains(s"OpWorkflow can contain at most 1 Model Selector. Found 2 Model Selectors :") shouldBe true
  }

  it should "throw an error when there is more than one ModelSelector in sequence" in {
    val ms1 = BinaryClassificationModelSelector()
    val ms2 = BinaryClassificationModelSelector()
    val (pred1, _, _) = ms1.setInput(label, features).getOutput()
    val (pred2, _, _) = ms2.setInput(pred1, features).getOutput()

    val error = intercept[IllegalArgumentException](cutDAG(wf.setResultFeatures(pred1, pred2)))
    error.getMessage
      .contains(s"OpWorkflow can contain at most 1 Model Selector. Found 2 Model Selectors :") shouldBe true
  }

  it should "optimize the DAG by removing stages no not related to model selection" in {
    val ms = BinaryClassificationModelSelector()
    val logReg = new OpLogisticRegression()
    val ldaFeatures = lda.setInput(features).getOutput()
    val checkedFeatures = sanityChecker.setInput(label2, ldaFeatures).getOutput()
    val (pred, _, _) = ms.setInput(label, features).getOutput()
    val (predLogReg, _, _) = logReg.setInput(label2, checkedFeatures).getOutput()

    assert(
      res = cutDAG(wf.setResultFeatures(pred, predLogReg)),
      modelSelector = Option(ms.stage1),
      nonCVTSDAG = Array(Array((lda, 2)), Array((sanityChecker, 1)), Array((logReg.stage1, 0))),
      cVTSDAG = Array.empty[Layer]
    )
  }

  it should "cut simple DAG without taking label transformation as cVTS stage" in {
    val ldaFeatures = lda.setInput(features).getOutput()
    val zNormalize = new OpScalarStandardScaler()
    val transformedLabel: FeatureLike[RealNN] = zNormalize.setInput(label).getOutput()
    val checkedFeatures = sanityChecker.setInput(transformedLabel, ldaFeatures).getOutput()
    val ms = BinaryClassificationModelSelector()
    val (pred, _, _) = ms.setInput(transformedLabel, checkedFeatures).getOutput()

    assert(
      res = cutDAG(wf.setResultFeatures(pred)),
      modelSelector = Option(ms.stage1),
      nonCVTSDAG = Array(Array((lda, 2), (zNormalize, 2))),
      cVTSDAG = Array(Array((sanityChecker, 1)))
    )
  }

  /**
   * Shortcut function to cut DAG
   *
   * @param wf Workflow
   * @return Cut DAG
   */
  private def cutDAG(wf: OpWorkflow): (Option[MS], StagesDAG, StagesDAG) = {
    wf.cutDAG(DAG.compute(wf.getResultFeatures()))
  }

  /**
   * Compare Actual and expected cut DAGs
   *
   * @param res             Actual results
   * @param modelSelector   Expected Model Selector
   * @param nonCVTSDAG Expected nonCVTS DAG
   * @param cVTSDAG   Expected cVTS DAG
   */
  private def assert(res: (Option[MS], StagesDAG, StagesDAG),
    modelSelector: Option[MS], nonCVTSDAG: StagesDAG, cVTSDAG: StagesDAG): Unit = {
    res._1 shouldBe modelSelector
    res._2 shouldBe nonCVTSDAG
    res._3 shouldBe cVTSDAG
  }
}
