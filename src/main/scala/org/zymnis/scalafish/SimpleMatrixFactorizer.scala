package org.zymnis.scalafish
//import org.zymnis.scalafish._
import org.zymnis.scalafish.matrix._

import Syntax._ // Matrix syntax

import scala.util.Random


object SimpleMatrixFactorizer extends App {
  def example(rows: Int, cols: Int, rank: Int, steps: Int = 4) {
    implicit val rng = new java.util.Random(1)

    val real = DenseMatrix.randLowRank(rows, cols, rank)
    val data = SparseMatrix.sample(0.01, real)
    val approx = DenseMatrix.zeros(rows, cols) // probably don't materialize this in the real deal
    val mf = new SimpleMatrixFactorizer(data, rank + 5, 1e-4f, 1e-1f)
    val nnz = data.nonZeros.toDouble
    (0 to steps).foreach{ i =>
      val obj = mf.currentObjective
      // println("iteration: " + i + ", obj: " + obj)
      if(true) {
        approx := mf.currentGuess
        approx -= real
        val relerr = math.sqrt(Matrix.frobNorm2(approx)) / math.sqrt(nnz)
        println("iteration: %d, RMSE: %4.3f".format(i, relerr))
      }
      mf.update
    }
  }

  example(1000, 100, 10, 20)
}

class SimpleMatrixFactorizer(data: SparseMatrix, rank: Int, mu: Float, alpha: Float) {
  implicit val rng = new java.util.Random(2)

  val L = DenseMatrix.rand(data.rows, rank)
  val R = DenseMatrix.rand(data.cols, rank)

  var iteration = 1

  private var currentDelta = SparseMatrix.zeros(data.rows, data.cols)

  var currentObjective: Double = computeObjective

  def computeObjective: Double =
    0.5 * (Matrix.frobNorm2(currentDelta) + mu * (Matrix.frobNorm2(L) + Matrix.frobNorm2(R)))

  def delta: MatrixUpdater = new ScalafishUpdater(L, R, data)

  def update {
    /*
  currentDelta = pat :* (L*R.t - data)

  update {
    val newAlpha = alpha / iteration
    L := L - (L * mu + currentDelta * R) * newAlpha
    R := R - (R * mu + currentDelta.t * L) * newAlpha
    iteration += 1
  }
     */
    val newAlpha = alpha / iteration

    currentDelta := delta
    currentDelta *= newAlpha

    L *= (1.0f - mu * newAlpha)
    L -= currentDelta * R

    currentDelta := delta
    // Slightly out of date by the end, but cheaper
    currentObjective = computeObjective
    currentDelta *= newAlpha

    R *= (1.0f - mu * newAlpha)
    R -= currentDelta.t * L

    iteration += 1
  }

  def currentGuess: MatrixUpdater = L * R.t
}
