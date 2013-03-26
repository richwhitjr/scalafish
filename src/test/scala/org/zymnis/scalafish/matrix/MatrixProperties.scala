package org.zymnis.scalafish.matrix

import org.scalacheck.{Arbitrary, Gen, Properties}
import org.scalacheck.Gen._
import org.scalacheck.Prop.forAll

import Syntax._

object MatrixProperties extends Properties("Matrix") {

  val FNORM_EPS = 1e-7

  def denseRandGen(rows: Int, cols: Int): Gen[Matrix] =
    Gen(_ => Some(DenseMatrix.rand(rows, cols)))

  def sparseRandGen(rows: Int, cols: Int, p: Double): Gen[Matrix] =
    denseRandGen(rows, cols).map { SparseMatrix.sample(p, _) }

  def denseWithNegs(rows: Int, cols: Int): Gen[Matrix] =
    Gen(_ => {
      val ones = DenseMatrix.zeros(rows, cols)
      ones := 1f
      val rand = DenseMatrix.rand(rows, cols)
      rand *= 2f
      rand := rand - ones
      Some(rand)
    })

  def sparseWithNegs(rows: Int, cols: Int, p: Double): Gen[Matrix] =
    denseWithNegs(rows, cols).map { SparseMatrix.sample(p, _) }

  def dense(rows: Int, cols: Int) = Gen.oneOf(denseRandGen(rows, cols), denseWithNegs(rows, cols))

  def sparse(rows: Int, cols: Int, p: Double) =
    Gen.oneOf(sparseRandGen(rows, cols, p), sparseWithNegs(rows, cols, p))

  def denseOrSparse(rows: Int, cols: Int, p: Double): Gen[Matrix] =
    Gen.oneOf(dense(rows, cols), sparse(rows, cols, p))

  trait MatrixCons {
    def apply(rows: Int, cols: Int): Matrix
  }

  def denseCons: Gen[MatrixCons] =
    Gen(_ => {
      Some(new MatrixCons {
        def apply(rows: Int, cols: Int) = DenseMatrix.zeros(rows, cols)
      })
    })

  def sparseCons: Gen[MatrixCons] =
    Gen(_ => Some(new MatrixCons {
        def apply(rows: Int, cols: Int) = SparseMatrix.zeros(rows, cols)
      })
    )

  val eitherCons = Gen.oneOf(denseCons, sparseCons)
  implicit val eitherArb: Arbitrary[MatrixCons] = Arbitrary(eitherCons)

  def newMatrix(rows: Int, cols: Int)(implicit cons: Arbitrary[MatrixCons]): Matrix = {
    cons.arbitrary.sample.get(rows, cols)
  }

  def fnormIsZero(fn: Double): Boolean =
    fn >= 0.0 && fn <= FNORM_EPS

  def isZero(m: Matrix): Boolean =
    fnormIsZero(Matrix.frobNorm2(m))

  // Expensive, but easy to check
  def row(m: Matrix, row: Int): IndexedSeq[Float] =
    (0 until m.cols).map { c => m(row, c) }

  def col(m: Matrix, col: Int): IndexedSeq[Float] =
    (0 until m.rows).map { r => m(r, col) }

  def dot(v1: IndexedSeq[Float], v2: IndexedSeq[Float]): Float =
    v1.view.zip(v2).map { case (l,r) => l * r }.sum

  def prod(set: Matrix, m1: Matrix, m2: Matrix): Unit =
    (0 until m1.rows).foreach { r =>
      (0 until m2.cols).foreach { c =>
        val thisD = dot(row(m1, r), col(m2, c))
        set.update(r, c, thisD)
      }
    }

  // for nxk matrices (to guarantee dimensions match): A B^T == (B A^T)^T
  def transposeLaw(rows: Int, cols: Int)(implicit cons: Arbitrary[MatrixCons]) = {
    val density = scala.math.random
    implicit val arb = Arbitrary(denseOrSparse(rows, cols, density))
    forAll { (a: Matrix, b: Matrix) =>
      val c = newMatrix(rows, rows)
      val d = newMatrix(rows, rows)
      val e = newMatrix(rows, rows)
      c := a * b.t
      d := b * a.t
      // First way to compute
      e := c - d.t
      val fnorm1 = Matrix.frobNorm2(e)
      // Inline
      c -= d.t
      val fnorm2 = Matrix.frobNorm2(c)
      fnormIsZero(fnorm1) && fnormIsZero(fnorm2)
    }
  }

  property("TransposeLaw 3x5 into Dense") = transposeLaw(3, 5)
  property("TransposeLaw 10x10") = transposeLaw(10, 10)
  property("TransposeLaw 10x1") = transposeLaw(10, 1)
  property("TransposeLaw 1x10") = transposeLaw(1, 10)
  property("TransposeLaw 1x1") = transposeLaw(1, 1)

  def productLaw(rows: Int, cols: Int)(implicit cons: Arbitrary[MatrixCons]) = {
    val density = scala.math.random
    implicit val arb = Arbitrary(denseOrSparse(rows, cols, density))

    forAll { (a: Matrix, b: Matrix) =>
      val temp1 = newMatrix(rows, rows)
      val temp2 = newMatrix(rows, rows)
      temp1 := a * b.t
      prod(temp2, a, b.t)
      temp1 -= temp2
      isZero(temp1)
    }
  }

  property("Product 10x100") = productLaw(10, 100)
  property("Product 10x10") = productLaw(10, 10)
  property("Product 10x1") = productLaw(10, 1)
  property("Product 1x1") = productLaw(1, 1)

  // (A + B) + C == A + (B + C)
  def additionLaws(rows: Int, cols: Int)(implicit cons: Arbitrary[MatrixCons]) = {
    val density = scala.math.random
    implicit val arb = Arbitrary(denseOrSparse(rows, cols, density))

    val eq = Equiv[Matrix].equiv _

    forAll { (a: Matrix, b: Matrix, c: Matrix) =>
      val temp1 = newMatrix(rows, cols)
      val temp2 = newMatrix(rows, cols)
      temp1 := (a + b) + c
      temp2 := a + (b + c)
      eq(temp1, temp2)
    }
  }

  property("Monoid + 10x100") = additionLaws(10, 100)
  property("Monoid + 10x10") = additionLaws(10, 10)
  property("Monoid + 10x1") = additionLaws(10, 1)
  property("Monoid + 1x1") = additionLaws(1, 1)

  // (x * M)(i,j) == x * (M(i,j))
  def scalarLaws(rows: Int, cols: Int)(implicit cons: Arbitrary[MatrixCons]) = {
    val density = scala.math.random
    implicit val arb = Arbitrary(denseOrSparse(rows, cols, density))
    implicit val smallFloats = Arbitrary(Gen.choose(-100.0f, 100.0f))
    val eq = Equiv[Matrix].equiv _

    forAll { (m: Matrix, f: Float) =>
      val temp = newMatrix(rows, cols)
      val temp2 = newMatrix(rows, cols)
      temp := m // copy
      temp *= f // Update in place
      temp2 := m * f // Don't change m
      eq(temp, temp2) && {
        (0 until rows).forall { r =>
          (0 until cols).forall { c =>
            val diff = scala.math.abs(temp(r, c) - (m(r, c) * f))
            diff < FNORM_EPS
          }
        }
      }
    }
  }

  property("Scalars work 10x100") = scalarLaws(10, 100)
  property("Scalars work 10x10") = scalarLaws(10, 10)
  property("Scalars work 10x1") = scalarLaws(10, 1)
  property("Scalars work 1x1") = scalarLaws(1, 1)

}
