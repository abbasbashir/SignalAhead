package com.signalahead.app.tracking
import com.signalahead.app.data.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
class BackupValidationTest{
    private fun empty()=Backup(networkSalt="12345678-1234-1234-1234-123456789abc",journeys=emptyList(),observations=emptyList(),legacySpots=emptyList(),legacyVotes=emptyList(),spots=emptyList(),votes=emptyList(),summaries=emptyList(),alerts=emptyList())
    @Test fun jsonRoundTrip(){val b=empty();val restored=Gson().fromJson(Gson().toJson(b),Backup::class.java);BackupManager.validate(restored);assertEquals(b,restored)}
    @Test(expected=IllegalArgumentException::class) fun futureFormatsRejected(){BackupManager.validate(empty().copy(format=99))}
    @Test(expected=IllegalArgumentException::class) fun outOfRangeCoordinatesRejected(){
        BackupManager.validate(empty().copy(spots=listOf(RouteSpot("a","a".repeat(64),999.0,0.0,0.0,0.0,0.0,"WEAK",.8,3,1))))
    }
}
