// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import scala.io.AnsiColor

import cats.data.NonEmptyList
import cats.effect.IO
import fs2.io.file.Path
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

trait DumboSpec extends ffstest.FTest {
  def db: Db

  def flywayMigrate(defaultSchema: String, sourcesPath: Path, schemas: List[String] = Nil): IO[MigrateResult] = IO(
    Flyway
      .configure()
      .defaultSchema(defaultSchema)
      .schemas(schemas*)
      .locations(sourcesPath.toString)
      .dataSource(
        s"jdbc:postgresql://localhost:$postgresPort/postgres?ssl=false",
        "root",
        null,
      )
      .load()
      .migrate()
  )

  def assertEqualHistory(histA: List[HistoryEntry], histB: List[HistoryEntry]): Unit = {
    def toCompare(h: HistoryEntry) =
      (h.installedRank, h.version, h.script, h.checksum, h.`type`, h.installedBy, h.success)

    assertEquals(histA.map(toCompare), histB.map(toCompare))
  }

  dbTest("Same behaviour on changed checksum") {
    val schema = "schema_1"

    for {
      res       <- flywayMigrate(schema, Path("db/test_0"))
      _          = assert(res.migrationsExecuted == 2)
      flywayRes <- flywayMigrate(schema, Path("db/test_0_changed_checksum")).attempt
      _          = assert(flywayRes.left.exists(_.getMessage().contains("checksum mismatch")))
      dumboRes  <- dumboMigrate(schema, dumboWithResources("db/test_0_changed_checksum")).attempt
      _          = assert(dumboRes.left.exists(_.getMessage().contains("checksum mismatch")))
    } yield ()
  }

  dbTest("Same behaviour on missing file") {
    val schema = "schema_1"

    for {
      res       <- flywayMigrate(schema, Path("db/test_0"))
      _          = assert(res.migrationsExecuted == 2)
      flywayRes <- flywayMigrate(schema, Path("db/test_0_missing_file")).attempt
      _          = assert(flywayRes.left.exists(_.getMessage().contains("Detected applied migration not resolved locally")))
      dumboRes  <- dumboMigrate(schema, dumboWithResources("db/test_0_missing_file")).attempt
      _          = assert(dumboRes.left.exists(_.isInstanceOf[dumbo.exception.DumboValidationException]))
      _          = assert(dumboRes.left.exists(_.getMessage().contains("Detected applied migration not resolved locally")))
    } yield ()
  }

  dbTest("Same behaviour on failing migration") {
    val schema = "schema_1"

    for {
      flywayRes <- flywayMigrate(schema, Path("db/test_failing_sql")).attempt
      _          = assert(flywayRes.isLeft)
      // Flyway does not provide more specific error message with CockroachDB in this case
      _ = if (Set(Db.Postgres16, Db.Postgres11).contains(db)) {
            assert(flywayRes.left.exists(_.getMessage().contains("relation \"test\" already exists")))
          }
      historyFlyway <- loadHistory(schema).map(h =>
                         db match {
                           case Db.Postgres11 | Db.Postgres16 => h
                           // Flyway is not able to run it within a transaction and rollback, so it adds a bistory entry with success false in CockroachDB
                           // going to ignore it in the test for now...
                           case Db.CockroachDb => h.filter(_.success == true)
                         }
                       )
      _        <- dropSchemas
      dumboRes <- dumboMigrate(schema, dumboWithResources("db/test_failing_sql")).attempt
      _         = assert(dumboRes.isLeft)
      _ = assert(
            dumboRes.left.exists(
              _.getMessage().linesIterator.exists(_.matches(""".*Relation ".*test" already exists.*"""))
            )
          )
      historyDumbo <- loadHistory(schema)
      _             = assertEqualHistory(historyFlyway, historyDumbo)
    } yield ()
  }

  dbTest("Dumbo is compatible with Flyway history state") {
    val path: Path     = Path("db/test_1")
    val withResources  = dumboWithResources("db/test_1")
    val withResourcesB = dumboWithResources("db/test_1_extended")
    val defaultSchema  = "test_a"

    for {
      flywayRes <- flywayMigrate(defaultSchema, path)
      _          = assert(flywayRes.success)
      _          = assertEquals(flywayRes.migrationsExecuted, 3)
      histA     <- loadHistory(defaultSchema)
      resDumbo  <- dumboMigrate(defaultSchema, withResources)
      _          = assertEquals(resDumbo.migrationsExecuted, 0)
      histB     <- loadHistory(defaultSchema)
      _          = assertEquals(histA, histB)                                           // history unchanged
      _         <- assertIO(dumboMigrate(defaultSchema, withResourcesB).map(_.migrationsExecuted), 1)
      _         <- assertIO(loadHistory(defaultSchema).map(_.length), histB.length + 1) // history extended
    } yield ()
  }

  dbTest("Flyway is compatible with Dumbo history state") {
    val path          = Path("db/test_1")
    val pathB         = Path("db/test_1_extended")
    val withResources = dumboWithResources("db/test_1")
    val defaultSchema = "test_a"

    for {
      resDumbo  <- dumboMigrate(defaultSchema, withResources)
      _          = assertEquals(resDumbo.migrationsExecuted, 3)
      histA     <- loadHistory(defaultSchema)
      flywayRes <- flywayMigrate(defaultSchema, path)
      _          = assert(flywayRes.success)
      _          = assertEquals(flywayRes.migrationsExecuted, 0)
      histB     <- loadHistory(defaultSchema)
      _          = assertEquals(histA, histB)                                           // history unchanged
      _         <- assertIO(flywayMigrate(defaultSchema, pathB).map(_.migrationsExecuted), 1)
      _         <- assertIO(loadHistory(defaultSchema).map(_.length), histB.length + 1) // history extended
    } yield ()
  }

  dbTest("Updates for different default schemas from Flyway to Dumbo") {
    val path: Path    = Path("db/test_1")
    val withResources = dumboWithResources("db/test_1")
    val schemaA       = "test_a"
    val schemaB       = "test_b"

    for {
      resFlywayA <- flywayMigrate(schemaA, path)
      resFlywayB <- flywayMigrate(schemaB, path)
      _           = assertEquals(resFlywayA.migrationsExecuted, 3)
      _           = assertEquals(resFlywayB.migrationsExecuted, 3)
      resDumboA  <- dumboMigrate(schemaA, withResources)
      resDumboB  <- dumboMigrate(schemaB, withResources)
      _           = assertEquals(resDumboA.migrationsExecuted, 0)
      _           = assertEquals(resDumboB.migrationsExecuted, 0)
    } yield ()
  }

  dbTest("Updates for different default schemas from Dumbo to Flyway") {
    val path: Path    = Path("db/test_1")
    val withResources = dumboWithResources("db/test_1")
    val schemaA       = "test_a"
    val schemaB       = "test_b"

    for {
      resDumboA <- dumboMigrate(schemaA, withResources)
      resDumboB <- dumboMigrate(schemaB, withResources)
      _          = assertEquals(resDumboA.migrationsExecuted, 3)
      _          = assertEquals(resDumboB.migrationsExecuted, 3)

      resFlywayA <- flywayMigrate(schemaA, path)
      resFlywayB <- flywayMigrate(schemaB, path)
      _           = assertEquals(resFlywayA.migrationsExecuted, 0)
      _           = assertEquals(resFlywayB.migrationsExecuted, 0)
    } yield ()
  }

  dbTest("Updates for multiple schemas with missing schema config") {
    val path: Path    = Path("db/test_three_schemas")
    val withResources = dumboWithResources("db/test_three_schemas")
    val schemas       = NonEmptyList.of("schema_1", "schema_2")

    for {
      flywayRes <- flywayMigrate(schemas.head, path, schemas.tail).attempt
      _          = assert(flywayRes.isLeft)
      flywayHistory <- loadHistory(schemas.head).map(h =>
                         db match {
                           case Db.Postgres11 | Db.Postgres16 => h
                           // Flyway is not able to run it within a transaction and rollback, so it adds a bistory entry with success false in CockroachDB
                           // going to ignore it in the test for now...
                           case Db.CockroachDb => h.filter(_.success == true)
                         }
                       )
      _            <- dropSchemas
      dumboRes     <- dumboMigrate(schemas.head, withResources, schemas.tail).attempt
      _             = assert(dumboRes.isLeft)
      dumboHistory <- loadHistory(schemas.head)
      _             = assertEqualHistory(flywayHistory, dumboHistory)
    } yield ()
  }

  dbTest("Updates for multiple schemas") {
    val path: Path    = Path("db/test_three_schemas")
    val withResources = dumboWithResources("db/test_three_schemas")
    val schemas       = NonEmptyList.of("schema_1", "schema_2", "schema_3")

    for {
      flywayRes     <- flywayMigrate(schemas.head, path, schemas.tail)
      _              = assert(flywayRes.migrationsExecuted == 1)
      flywayHistory <- loadHistory(schemas.head)
      _             <- dropSchemas
      dumboRes      <- dumboMigrate(schemas.head, withResources, schemas.tail)
      _              = assert(dumboRes.migrationsExecuted == 1)
      dumboHistory  <- loadHistory(schemas.head)
      _              = assertEqualHistory(flywayHistory, dumboHistory)
    } yield ()
  }

  dbTest("Same behaviour on non-transactional operations") {
    val path: Path    = Path("db/test_non_transactional")
    val withResources = dumboWithResources("db/test_non_transactional")
    val schema        = "schema_1"

    if (db == Db.Postgres16)
      for {
        flywayRes <- flywayMigrate(schema, path).attempt
        _ =
          assert(
            flywayRes.left.exists(_.getMessage().contains("New enum values must be committed before they can be used"))
          )
        flywayHistory <- loadHistory(schema)
        _             <- dropSchemas
        dumboRes      <- dumboMigrate(schema, withResources).attempt
        _ = assert(
              dumboRes.left.exists(_.getMessage().contains("New enum values must be committed before they can be used"))
            )
        dumboHistory <- loadHistory(schema)
        _             = assertEqualHistory(flywayHistory, dumboHistory)
      } yield ()
    else
      IO.println(
        s"${AnsiColor.YELLOW}[$db] Skipping test 'Same behaviour on non-transactional operations' as Flyway can't run the statements in a transaction${AnsiColor.RESET}"
      )
  }

  dbTest("Same behavior on copy") {
    val path: Path    = Path("db/test_copy_to")
    val withResources = dumboWithResources("db/test_copy_to")
    val schema        = "schema_1"

    for {
      flywayRes <- flywayMigrate(schema, path).attempt
      _          = assert(flywayRes.isLeft)
      _         <- dropSchemas
      dumboRes  <- dumboMigrate(schema, withResources).attempt
      _          = assert(dumboRes.left.exists(_.isInstanceOf[skunk.exception.CopyNotSupportedException]))
    } yield ()
  }
}

sealed trait Db
object Db {
  case object Postgres11  extends Db
  case object Postgres16  extends Db
  case object CockroachDb extends Db
}

class DumboFlywaySpecPostgresLatest extends DumboSpec {
  override val db: Db            = Db.Postgres16
  override val postgresPort: Int = 5433
}

class DumboFlywaySpecPostgres11 extends DumboSpec {
  override val db: Db            = Db.Postgres11
  override val postgresPort: Int = 5435
}

class DumboFlywaySpecCockroachDb extends DumboSpec {
  override val db: Db            = Db.CockroachDb
  override val postgresPort: Int = 5437
}
