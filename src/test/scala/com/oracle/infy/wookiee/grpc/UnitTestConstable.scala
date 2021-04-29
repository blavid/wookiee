package com.oracle.infy.wookiee.grpc

import cats.effect.{ConcurrentEffect, IO}
import cats.implicits._
import com.oracle.infy.wookiee.grpc.common.ConstableCommon
import com.oracle.infy.wookiee.grpc.contract.ListenerContract
import com.oracle.infy.wookiee.grpc.impl.{Fs2CloseableImpl, MockHostNameService, WookieeGrpcHostListener}
import com.oracle.infy.wookiee.grpc.tests.{GrpcListenerTest, SerdeTest}
import com.oracle.infy.wookiee.model.Host
import fs2.Stream
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.noop.NoOpLogger

import scala.concurrent.ExecutionContext
import cats.effect.Deferred

object UnitTestConstable extends ConstableCommon {

  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = mainExecutionContext(4)
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    val blockingEC: ExecutionContext = blockingExecutionContext("unit-test")
    val blocker = Blocker.liftExecutionContext(blockingEC)
    implicit val concurrent: ConcurrentEffect[IO] = IO.ioConcurrentEffect

    def pushMessagesFuncAndListenerFactory(
        callback: Set[Host] => IO[Unit]
    ): IO[(Set[Host] => IO[Unit], () => IO[Unit], ListenerContract[IO, Stream])] = {
      for {
        logger <- NoOpLogger.impl[IO].pure[IO]
        queue <- Queue.unbounded[IO, Set[Host]]
        killswitch <- Deferred[IO, Either[Throwable, Unit]]

      } yield {
        val pushMessagesFunc = { hosts: Set[Host] =>
          queue.enqueue1(hosts)
        }
        val listener: ListenerContract[IO, Stream] =
          new WookieeGrpcHostListener(
            callback,
            new MockHostNameService(Fs2CloseableImpl(queue.dequeue, killswitch)),
            discoveryPath = ""
          )(cs, blocker, logger)

        val cleanup: () => IO[Unit] = () => {
          IO(())
        }

        (pushMessagesFunc, cleanup, listener)
      }
    }

    val grpcTests = GrpcListenerTest.tests(100, pushMessagesFuncAndListenerFactory)

    exitNegativeOnFailure(
      runTestsAsync(
        List(
          (SerdeTest.tests, "UnitTest - Serde"),
          (grpcTests, "UnitTest - GRPC Tests")
        )
      )
    )
  }

}
