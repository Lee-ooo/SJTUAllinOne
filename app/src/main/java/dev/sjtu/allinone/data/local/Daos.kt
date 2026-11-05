package dev.sjtu.allinone.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE accountId=:accountId")
    fun observe(accountId: String): Flow<List<CourseEntity>>

    @Query("SELECT * FROM semesters WHERE accountId=:accountId ORDER BY year DESC, semester DESC")
    fun observeSemesters(accountId: String): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters WHERE accountId=:accountId AND year=:year AND semester=:semester")
    suspend fun semester(accountId: String, year: String, semester: Int): SemesterEntity?

    @Upsert
    suspend fun saveSemester(item: SemesterEntity)

    @Query("SELECT * FROM course_overrides WHERE accountId=:accountId")
    fun observeOverrides(accountId: String): Flow<List<CourseOverrideEntity>>

    @Query("SELECT * FROM courses WHERE accountId=:accountId AND year=:year AND semester=:semester")
    suspend fun forSemester(accountId: String, year: String, semester: Int): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE accountId=:accountId AND date=:date")
    fun onDateNow(accountId: String, date: Long): List<CourseEntity>

    @Query("SELECT * FROM course_overrides WHERE accountId=:accountId")
    fun overridesNow(accountId: String): List<CourseOverrideEntity>

    @Query("SELECT * FROM courses WHERE id=:id AND accountId=:accountId")
    suspend fun byId(accountId: String, id: String): CourseEntity?

    @Query("SELECT * FROM course_overrides WHERE id=:id AND accountId=:accountId")
    suspend fun overrideById(accountId: String, id: String): CourseOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(items: List<CourseEntity>)

    @Query("SELECT * FROM semesters WHERE accountId=:accountId AND year=:year AND semester=:semester")
    fun semesterNow(accountId: String, year: String, semester: Int): SemesterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOverride(item: CourseOverrideEntity)

    @Query("DELETE FROM course_overrides WHERE id=:id AND accountId=:accountId")
    suspend fun removeOverride(accountId: String, id: String)

    @Query("DELETE FROM courses WHERE id=:id AND accountId=:accountId AND source='local'")
    suspend fun deleteLocal(accountId: String, id: String)

    @Query("DELETE FROM courses WHERE accountId=:accountId AND year=:year AND semester=:semester AND source='school'")
    suspend fun deleteRemote(accountId: String, year: String, semester: Int)

    @Query("DELETE FROM courses WHERE accountId=:accountId AND year=:year AND semester=:semester")
    suspend fun deleteSemesterCourses(accountId: String, year: String, semester: Int)

    @Query("DELETE FROM semesters WHERE accountId=:accountId AND year=:year AND semester=:semester")
    suspend fun deleteSemester(accountId: String, year: String, semester: Int)

    @Query("DELETE FROM courses WHERE accountId=:accountId")
    suspend fun clear(accountId: String)

    @Query("DELETE FROM semesters WHERE accountId=:accountId")
    suspend fun clearSemesters(accountId: String)

    @Query("DELETE FROM course_overrides WHERE accountId=:accountId")
    suspend fun clearOverrides(accountId: String)
}

@Dao
interface AssignmentDao {
    @Query("SELECT * FROM assignments WHERE accountId=:accountId")
    fun observe(accountId: String): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM assignments WHERE accountId=:accountId AND id=:id")
    suspend fun byId(accountId: String, id: String): AssignmentEntity?

    @Query("SELECT * FROM assignments WHERE accountId=:accountId")
    suspend fun allNow(accountId: String): List<AssignmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<AssignmentEntity>)

    @Upsert suspend fun save(item: AssignmentEntity)

    @Query("DELETE FROM assignments WHERE accountId=:accountId AND id=:id AND source='local'")
    suspend fun deleteLocal(accountId: String, id: String)

    @Query("DELETE FROM assignments WHERE accountId=:accountId AND source='canvas' AND courseId=:courseId")
    suspend fun deleteRemoteCourse(accountId: String, courseId: String)

    @Query("DELETE FROM assignments WHERE accountId=:accountId AND source='canvas' AND courseId NOT IN (:courseIds)")
    suspend fun deleteRemoteNotIn(accountId: String, courseIds: List<String>)

    @Query("DELETE FROM assignments WHERE accountId=:accountId AND source='canvas'")
    suspend fun deleteAllRemote(accountId: String)

    @Query("DELETE FROM assignments WHERE accountId=:accountId")
    suspend fun clear(accountId: String)
}

@Dao
interface AcademicDao {
    @Query("SELECT * FROM exams WHERE accountId=:accountId")
    fun observeExams(accountId: String): Flow<List<ExamEntity>>

    @Query("SELECT * FROM grades WHERE accountId=:accountId")
    fun observeGrades(accountId: String): Flow<List<GradeEntity>>

    @Query("SELECT * FROM grades WHERE accountId=:accountId")
    suspend fun gradesNow(accountId: String): List<GradeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExams(items: List<ExamEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrades(items: List<GradeEntity>)

    @Upsert suspend fun saveGrade(item: GradeEntity)

    @Query("DELETE FROM exams WHERE accountId=:accountId AND year=:year AND semester=:semester")
    suspend fun deleteExams(accountId: String, year: String, semester: Int)

    @Query("DELETE FROM grades WHERE accountId=:accountId")
    suspend fun deleteGrades(accountId: String)

    @Query("DELETE FROM exams WHERE accountId=:accountId")
    suspend fun clearExams(accountId: String)

    @Query("DELETE FROM grades WHERE accountId=:accountId")
    suspend fun clearGrades(accountId: String)
}

@Dao
interface RoomDao {
    @Query("SELECT * FROM buildings WHERE accountId=:accountId ORDER BY campus, name")
    fun observeBuildings(accountId: String): Flow<List<BuildingEntity>>

    @Query("SELECT * FROM rooms WHERE accountId=:accountId")
    fun observeRooms(accountId: String): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms WHERE accountId=:accountId AND buildingId=:buildingId")
    suspend fun roomsNow(accountId: String, buildingId: String): List<RoomEntity>

    @Query("SELECT * FROM occupations WHERE accountId=:accountId AND date=:date")
    fun observeOccupations(accountId: String, date: Long): Flow<List<OccupationEntity>>

    @Query("SELECT * FROM rooms WHERE accountId=:accountId AND id=:roomId")
    suspend fun room(accountId: String, roomId: String): RoomEntity?

    @Query("SELECT * FROM sections WHERE accountId=:accountId ORDER BY sectionIndex")
    fun observeSections(accountId: String): Flow<List<SectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(items: List<SectionEntity>)

    @Query("DELETE FROM sections WHERE accountId=:accountId")
    suspend fun clearSections(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuildings(items: List<BuildingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRooms(items: List<RoomEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccupations(items: List<OccupationEntity>)

    @Upsert suspend fun saveRoom(item: RoomEntity)

    @Query("DELETE FROM buildings WHERE accountId=:accountId")
    suspend fun deleteBuildings(accountId: String)

    @Query("DELETE FROM occupations WHERE accountId=:accountId AND roomId IN (SELECT id FROM rooms WHERE accountId=:accountId AND buildingId=:buildingId) AND date=:date")
    suspend fun deleteOccupations(accountId: String, buildingId: String, date: Long)

    @Query("DELETE FROM buildings WHERE accountId=:accountId")
    suspend fun clearBuildings(accountId: String)

    @Query("DELETE FROM rooms WHERE accountId=:accountId")
    suspend fun clearRooms(accountId: String)

    @Query("DELETE FROM occupations WHERE accountId=:accountId")
    suspend fun clearOccupations(accountId: String)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_states WHERE accountId=:accountId")
    fun observe(accountId: String): Flow<List<SyncStateEntity>>

    @Query("SELECT * FROM sync_states WHERE accountId=:accountId AND source=:source")
    suspend fun get(accountId: String, source: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: SyncStateEntity)

    @Query("DELETE FROM sync_states WHERE accountId=:accountId")
    suspend fun clear(accountId: String)
}
