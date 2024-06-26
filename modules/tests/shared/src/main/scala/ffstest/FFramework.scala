// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ffstest

import scala.concurrent.duration.*
import scala.util.Random

import cats.data.ValidatedNec
import cats.effect.{IO, Resource, std}
import cats.implicits.*
import dumbo.exception.DumboValidationException
import dumbo.{ConnectionConfig, Dumbo, DumboWithResourcesPartiallyApplied, History, HistoryEntry}
import munit.CatsEffectSuite
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import skunk.Session
import skunk.implicits.*

trait FTest extends CatsEffectSuite with FTestPlatform {
  def postgresPort: Int = 5432

  def dbTest(name: String)(f: => IO[Unit]): Unit = test(name)(dropSchemas >> f)

  def someSchemaName = s"schema_${Random.alphanumeric.take(10).mkString}"

  lazy val connectionConfig: ConnectionConfig = ConnectionConfig(
    host = "localhost",
    port = postgresPort,
    user = "root",
    database = "postgres",
    password = None,
  )

  def session: Resource[IO, Session[IO]] = Session
    .single[IO](
      host = connectionConfig.host,
      port = connectionConfig.port,
      user = connectionConfig.user,
      database = connectionConfig.database,
      password = connectionConfig.password,
    )

  def loadHistory(schema: String, tableName: String = "flyway_schema_history"): IO[List[HistoryEntry]] =
    session.use(_.execute(History(s"$schema.$tableName").loadAllQuery))

  def dumboMigrate(
    defaultSchema: String,
    withResources: DumboWithResourcesPartiallyApplied[IO],
    schemas: List[String] = Nil,
    schemaHistoryTable: String = "flyway_schema_history",
    validateOnMigrate: Boolean = true,
    logMigrationStateAfter: Duration = Duration.Inf,
  )(implicit c: std.Console[IO]): IO[Dumbo.MigrationResult] =
    (if (logMigrationStateAfter.isFinite) {
       withResources.withMigrationStateLogAfter(FiniteDuration(logMigrationStateAfter.toMillis, MILLISECONDS))(
         connection = connectionConfig,
         defaultSchema = defaultSchema,
         schemas = schemas.toSet,
         schemaHistoryTable = schemaHistoryTable,
         validateOnMigrate = validateOnMigrate,
       )
     } else {
       withResources.apply(
         connection = connectionConfig,
         defaultSchema = defaultSchema,
         schemas = schemas.toSet,
         schemaHistoryTable = schemaHistoryTable,
         validateOnMigrate = validateOnMigrate,
       )
     }).runMigration

  def validateWithAppliedMigrations(
    defaultSchema: String,
    withResources: DumboWithResourcesPartiallyApplied[IO],
    schemas: List[String] = Nil,
  ): IO[ValidatedNec[DumboValidationException, Unit]] =
    withResources(
      connection = connectionConfig,
      defaultSchema = defaultSchema,
      schemas = schemas.toSet,
    ).runValidationWithHistory

  def dropSchemas: IO[Unit] = session.use { s =>
    for {
      customSchemas <-
        s.execute(
          sql"""
        SELECT schema_name::text
        FROM information_schema.schemata 
        WHERE schema_name NOT LIKE 'pg_%' AND schema_name NOT LIKE 'crdb_%' AND schema_name NOT IN ('information_schema', 'public')"""
            .query(skunk.codec.text.text)
        )
      _ <- IO.println(s"Dropping schemas ${customSchemas.mkString(", ")}")
      c <- customSchemas.traverse(schema => s.execute(sql"DROP SCHEMA IF EXISTS #${schema} CASCADE".command))
      _ <- IO.println(s"Schema drop result ${c.mkString(", ")}")
    } yield ()
  }
}
