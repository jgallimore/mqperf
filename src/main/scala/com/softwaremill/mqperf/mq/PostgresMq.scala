package com.softwaremill.mqperf.mq

import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.{Executors, TimeoutException}
import cats.data.NonEmptyList
import com.softwaremill.mqperf.config.TestConfig
import com.zaxxer.hikari.HikariConfig
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.implicits.javatime._
import cats.implicits._
import cats.effect._
import doobie.hikari.HikariTransactor

import scala.concurrent.duration._
import doobie.util.log.{ExecFailure, ProcessingFailure, Success}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

class PostgresMq(testConfig: TestConfig) extends Mq {
  private val clock = java.time.Clock.systemUTC()
  override type MsgId = UUID
  private val supportEC: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  implicit val cs: ContextShift[IO] = IO.contextShift(supportEC)
  implicit val timer: Timer[IO] = IO.timer(supportEC)

  val transactorResource: Resource[IO, Transactor[IO]] = {
    /*
     * When running DB operations, there are three thread pools at play:
     * (1) connectEC: this is a thread pool for awaiting connections to the database. There might be an arbitrary
     * number of clients waiting for a connection, so this should be bounded.
     * (2) transactEC: this is a thread pool for executing JDBC operations. As the connection pool is limited,
     * this can be unbounded pool
     * (3) contextShift: pool for executing non-blocking operations, to which control shifts after completing
     * DB operations. This is provided by Monix for Task.
     *
     * See also: https://tpolecat.github.io/doobie/docs/14-Managing-Connections.html#about-threading
     */
    for {
      connectEC <- doobie.util.ExecutionContexts.fixedThreadPool[IO](testConfig.receiverThreads)
      transactEC <- doobie.util.ExecutionContexts.cachedThreadPool[IO]
      _ <- Resource.liftF(Async[IO].delay(Class.forName("org.postgresql.Driver")))
      xa <- HikariTransactor.fromHikariConfig[IO]( {
        val hc = new HikariConfig()
        hc.setAutoCommit(false)
        hc.setConnectionInitSql("set time zone 'UTC'")
        hc.setJdbcUrl(s"jdbc:postgresql://${testConfig.brokerHosts.head}:5432/mq")
        hc.setUsername("mq")
        hc.setPassword("pass")
        hc.setMaxLifetime(5 * 60 * 1000)
        hc.setMaximumPoolSize(testConfig.receiverThreads)
        hc
      } ,connectEC, Blocker.liftExecutionContext(transactEC))
    } yield xa
  }

  lazy val (transactor, closingHandle) =
    transactorResource.allocated.unsafeRunTimed(1 minute).getOrElse(throw new TimeoutException())

  override def close(): Unit = {
    closingHandle.unsafeRunTimed(10 seconds).getOrElse(throw new TimeoutException())
  }

  private def createJobTable(): Unit = {
    (sql"""CREATE TABLE IF NOT EXISTS jobs(ID UUID PRIMARY KEY, CONTENT TEXT NOT NULL, NEXT_DELIVERY TIMESTAMPTZ NOT NULL)""".update.run >>
    Update0("CREATE INDEX IF NOT EXISTS next_delivery_idx ON jobs USING BTREE(NEXT_DELIVERY);",None).run)
      .transact(transactor)
      .unsafeRunTimed(10 seconds)
      .getOrElse(throw new TimeoutException())
  }

  override def createSender(): MqSender = {
    createJobTable()
    new MqSender {
      def insertMany(ps: List[String]): ConnectionIO[Int] = {
        val sql = "insert into jobs(id, content, next_delivery) values (?, ?, ?)"
        Update[(UUID, String, OffsetDateTime)](sql).updateMany(ps.map(c => (UUID.randomUUID(), c, OffsetDateTime.now(clock))))
      }

      /**
        * Synchronous - must wait for the messages to be sent
        */
      override def send(msgs: List[String]): Unit = {
        insertMany(msgs)
          .transact(transactor)
          .unsafeRunTimed(10 seconds)
          .getOrElse(throw new TimeoutException())
      }
    }
  }

  implicit val doobieLogHandler: LogHandler = LogHandler {
    case Success(sql, args, exec, processing) =>
    case ProcessingFailure(sql, args, _, _, failure) =>
      println(s"Processing failure: $sql | args: $args $failure")
    case ExecFailure(sql, args, _, failure) =>
      println(s"Execution failure: $sql | args: $args $failure")
  }

  override def createReceiver(): MqReceiver = {
    createJobTable()
    new MqReceiver {
      override def receive(maxMsgCount: Int): List[(MsgId, String)] = {
        val now = OffsetDateTime.now(clock)
        val nextDelivery = now.plusSeconds(100)
        val nextJobs =
          (sql"SELECT id, content FROM jobs WHERE next_delivery <= $now FOR UPDATE SKIP LOCKED LIMIT " ++ Fragment
            .const(testConfig.receiveMsgBatchSize.toString))
            .query[(MsgId, String)]
            .to[List]
        nextJobs
          .flatMap {
            case Nil => List.empty[(MsgId, String)].pure[ConnectionIO]
            case jobs @ head :: tl =>
              (sql"UPDATE jobs SET next_delivery = $nextDelivery WHERE " ++ Fragments.in(
                fr"id",
                NonEmptyList(head, tl).map(_._1)
              )).update.run
                .as(jobs.map(t => t._1 -> t._2))
          }
          .transact(transactor)
          .unsafeRunTimed(10 seconds)
          .getOrElse(throw new TimeoutException())
      }

      /**
        * Can be asynchronous
        */
      override def ack(ids: List[UUID]): Unit =
        (sql"DELETE FROM jobs WHERE " ++ Fragments.in(fr"id", NonEmptyList.fromListUnsafe(ids))).update.run.void
          .transact(transactor)
          .unsafeRunTimed(10 seconds)
          .getOrElse(throw new TimeoutException())
    }
  }
}
