package org.zymnis.scalafish

import akka.actor._
import akka.dispatch.{Await, ExecutionContext, Future}
import akka.pattern.ask
import akka.util.{Duration, Timeout}
import akka.util.duration._

import scala.util.Random

import org.zymnis.scalafish.matrix._

object DistributedMatrixFactorizer extends App {
  import Syntax._

  implicit val rng = new java.util.Random(1)

  val rows = 1000
  val cols = 100
  val realRank = 10
  val factorRank = 15
  val slices = 5
  val p = 0.1
  val mu = 1e-4
  val alpha = 5e-2
  val iters = 1000

  val real = DenseMatrix.randLowRank(rows, cols, realRank)
  val dataMat = SparseMatrix.sample(p, real)
  val data = dataMat.rowSlice(slices)

  val system = ActorSystem("FactorizerSystem")
  val master = system.actorOf(Props(new Master(cols, factorRank, slices, mu, alpha, iters)), name = "master")
  master ! StartMaster(data)
}

// Messages are defined below

sealed trait FactorizerMessage
case class UpdateWorkerData(data: Matrix) extends FactorizerMessage
case class UpdateWorkerR(newR: IndexedSeq[Matrix]) extends FactorizerMessage
case class StartMaster(data: IndexedSeq[Matrix]) extends FactorizerMessage
case object DoUpdate extends FactorizerMessage
case class UpdateResponse(rSlice: Matrix, rIndex: Int, objSlice: Double) extends FactorizerMessage
case object DataUpdated extends FactorizerMessage
case object GetCurrentL extends FactorizerMessage
case class CurrentL(myL: Matrix) extends FactorizerMessage

// Actors start here

class Master(cols: Int, rank: Int, slices: Int, mu: Double, alpha: Double, iters: Int) extends Actor {
  import Syntax._

  val timeToWait = 5.seconds

  implicit val timeout = Timeout(timeToWait)
  implicit val ec = ExecutionContext.defaultExecutionContext(context.system)
  implicit val rng = new java.util.Random(2)

  var R: Vector[Matrix] = _
  var data: IndexedSeq[Matrix] = _
  val actors = (0 until slices).map{ ind =>
    context.actorOf(Props(new Worker(ind, cols, rank, slices, mu, alpha)), name = "worker_" + ind)
  }

  override def preStart() = {
    println("starting up master")
    R = Vector.fill(slices)(DenseMatrix.rand(cols / slices, rank))
  }

  def receive = {
    case StartMaster(data) => {
      this.data = data

      println("initializing workers")
      updateWorkerData(data).flatMap { _ =>
        updateWorkerR
      }.flatMap { _ =>
        steps(iters) { i =>
          doWorkerUpdates.flatMap { obj =>
            // println("Master iteration: %s".format(iters - i))
            updateWorkerR
          }.flatMap { _ =>
            if (i % 20 == 0) {
              printCurrentError(iters - i)
            } else {
              Future(())
            }
          }
        }
      }
      .flatMap { _ => printCurrentError(iters) }
      .foreach { _ =>
        context.stop(self)
        context.system.shutdown()
      }
    }
  }

  def steps(numSteps: Int)(f: (Int) => Future[Unit]): Future[Unit] = {
    if(numSteps <= 0) Future(())
    else {
      f(numSteps).flatMap { _ => steps(numSteps - 1)(f) }
    }
  }

  def printCurrentError(iter: Int): Future[Unit] = {
    lookupL.map { lSeq =>
      val nnz = data.map{ _.nonZeros.toDouble }.sum
      val rows = data.map { _.rows }.sum
      val out = SparseMatrix.zeros(rows, cols)
      out := new ScalafishUpdater(Matrix.vStack(lSeq), Matrix.vStack(R), Matrix.vStack(data))
      val approxFrobNorm = out.frobNorm2
      println("iter: %d, RMSE: %.3f".format(iter, math.sqrt(approxFrobNorm / nnz)))
    }
  }

  def updateWorkerData(data: Iterable[Matrix]): Future[Unit] = {
    val futures = data.zip(actors).map{
      case(mat, worker) => worker ? UpdateWorkerData(mat)
    }
    Future.sequence(futures).map { _ => () }
  }

  def updateWorkerR: Future[Unit] = {
    val futures = actors.map{
      worker => worker ? UpdateWorkerR(R)
    }
    Future.sequence(futures).map { _ => () }
  }

  def doWorkerUpdates: Future[Double] = {
    val futures = actors.map{ worker =>
      (worker ? DoUpdate).mapTo[UpdateResponse].map { res =>
        R(res.rIndex) := res.rSlice
        res.objSlice
      }
    }
    Future.reduce(futures)(_ + _)
  }

  def lookupL: Future[Seq[Matrix]] = {
    Future.sequence {
      actors.map { worker =>
        (worker ? GetCurrentL)
          .mapTo[CurrentL]
          .map { _.myL }
      }
    }
  }
}

class Worker(workerIndex: Int, cols: Int, rank: Int, slices: Int, mu: Double, alpha: Double) extends Actor {
  implicit val rng = new java.util.Random(workerIndex)

  import Syntax._

  var R: IndexedSeq[DenseMatrix] = _
  var L: DenseMatrix = _
  var data: IndexedSeq[Matrix] = _
  var currentDelta: IndexedSeq[Matrix] = _
  var iteration = 0

  val sliceSize = cols / slices

  override def preStart() = {
    R = Vector.fill(slices)(DenseMatrix.zeros(sliceSize, rank))
  }

  def receive = {
    case UpdateWorkerData(mat) => {
      L = DenseMatrix.rand(mat.rows, rank)
      // TODO keep a bunch of these and overwrite the one with the pattern of data(N)
      currentDelta = Vector.fill(slices)(SparseMatrix.zeros(mat.rows, sliceSize))
      data = mat.colSlice(slices)
      sender ! DataUpdated
    }
    case UpdateWorkerR(mats) => {
      mats.zipWithIndex.foreach{ case(r, ind) => R(ind) := r }
      sender ! DataUpdated
    }
    case DoUpdate => {
      val updateIndex = (iteration + workerIndex) % slices
      val Rn = R(updateIndex)
      val dataN = data(updateIndex)
      val deltaN = currentDelta(updateIndex)
      def delta = new ScalafishUpdater(L, Rn, dataN)
      val currentAlpha = (alpha / (iteration + 1)).toFloat

      deltaN := delta
      deltaN *= currentAlpha

      L *= (1.0f - mu.toFloat * currentAlpha)
      L -= deltaN * Rn

      deltaN := delta
      deltaN *= currentAlpha

      Rn *= (1.0f - mu.toFloat * currentAlpha)
      Rn -= deltaN.t * L

      val curObj = 0.5 * (0 + mu.toFloat * (Matrix.frobNorm2(L) + Matrix.frobNorm2(Rn)))
      iteration += 1
      sender ! UpdateResponse(Rn, updateIndex, curObj)
    }
    case GetCurrentL => sender ! CurrentL(L)
  }
}
