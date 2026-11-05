package dev.sjtu.allinone.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [
        SemesterEntity::class, CourseEntity::class, CourseOverrideEntity::class,
        AssignmentEntity::class, ExamEntity::class, GradeEntity::class,
        BuildingEntity::class, RoomEntity::class, SectionEntity::class, OccupationEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courses(): CourseDao
    abstract fun assignments(): AssignmentDao
    abstract fun academics(): AcademicDao
    abstract fun rooms(): RoomDao
    abstract fun sync(): SyncDao

    suspend fun clearAccount(accountId: String) = withTransaction {
        courses().clear(accountId)
        courses().clearSemesters(accountId)
        courses().clearOverrides(accountId)
        assignments().clear(accountId)
        academics().clearExams(accountId)
        academics().clearGrades(accountId)
        rooms().clearBuildings(accountId)
        rooms().clearRooms(accountId)
        rooms().clearOccupations(accountId)
        rooms().clearSections(accountId)
        sync().clear(accountId)
    }

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, "sjtu.db",
        ).build()
    }
}
