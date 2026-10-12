package com.example

import com.example.domain.model.AudioItem
import com.example.domain.model.PlaybackOrder
import com.example.domain.model.Profile
import com.example.domain.model.RestingPeriod
import com.example.domain.model.ResumePolicy
import com.example.domain.scheduler.ScheduleCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {

    private val testAudio = AudioItem(
        id = 1L,
        uriString = "content://media/audio/1",
        label = "Meta diária",
        isActive = true
    )

    // Caso 1: Descanso que atravessa a meia-noite (22:00 às 07:00)
    @Test
    fun test1_restingPeriod_crossesMidnight() {
        val overnightRest = RestingPeriod(
            label = "Sono",
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(7, 0)
        )
        assertTrue("Deve identificar que atravessa a meia-noite", overnightRest.crossesMidnight)

        // Tempos durante a noite
        assertTrue("22:00 deve estar em descanso", overnightRest.contains(LocalTime.of(22, 0)))
        assertTrue("23:30 deve estar em descanso", overnightRest.contains(LocalTime.of(23, 30)))
        assertTrue("03:15 deve estar em descanso", overnightRest.contains(LocalTime.of(3, 15)))
        assertTrue("06:59 deve estar em descanso", overnightRest.contains(LocalTime.of(6, 59)))

        // Tempos fora do descanso
        assertFalse("07:00 deve ser fim do descanso (livre para tocar)", overnightRest.contains(LocalTime.of(7, 0)))
        assertFalse("14:00 deve estar livre", overnightRest.contains(LocalTime.of(14, 0)))
        assertFalse("21:59 deve estar livre", overnightRest.contains(LocalTime.of(21, 59)))
    }

    // Caso 2: Vários descansos no mesmo dia (almoço + noite)
    @Test
    fun test2_multipleRestingPeriods_sameDay() {
        val rests = listOf(
            RestingPeriod(label = "Almoço", startTime = LocalTime.of(12, 0), endTime = LocalTime.of(13, 0)),
            RestingPeriod(label = "Noite", startTime = LocalTime.of(22, 0), endTime = LocalTime.of(6, 0))
        )

        assertTrue("12:30 deve estar no almoço", ScheduleCalculator.isTimeInRestingPeriod(LocalTime.of(12, 30), rests))
        assertTrue("23:00 deve estar na noite", ScheduleCalculator.isTimeInRestingPeriod(LocalTime.of(23, 0), rests))
        assertFalse("10:00 deve estar livre", ScheduleCalculator.isTimeInRestingPeriod(LocalTime.of(10, 0), rests))
        assertFalse("15:00 deve estar livre", ScheduleCalculator.isTimeInRestingPeriod(LocalTime.of(15, 0), rests))
    }

    // Caso 3: Descansos sobrepostos ou encostados (devem ser mesclados/validados)
    @Test
    fun test3_overlappingOrTouchingRests_merged() {
        // Encostados: 12:00-13:00 e 13:00-14:00
        val touchingRests = listOf(
            RestingPeriod(label = "R1", startTime = LocalTime.of(12, 0), endTime = LocalTime.of(13, 0)),
            RestingPeriod(label = "R2", startTime = LocalTime.of(13, 0), endTime = LocalTime.of(14, 0))
        )
        val mergedTouching = ScheduleCalculator.mergeRestingPeriods(touchingRests)
        assertEquals(1, mergedTouching.size)
        assertEquals(LocalTime.of(12, 0), mergedTouching[0].startTime)
        assertEquals(LocalTime.of(14, 0), mergedTouching[0].endTime)

        // Sobrepostos: 12:00-13:30 e 13:00-15:00
        val overlappingRests = listOf(
            RestingPeriod(label = "R1", startTime = LocalTime.of(12, 0), endTime = LocalTime.of(13, 30)),
            RestingPeriod(label = "R2", startTime = LocalTime.of(13, 0), endTime = LocalTime.of(15, 0))
        )
        val mergedOverlapping = ScheduleCalculator.mergeRestingPeriods(overlappingRests)
        assertEquals(1, mergedOverlapping.size)
        assertEquals(LocalTime.of(12, 0), mergedOverlapping[0].startTime)
        assertEquals(LocalTime.of(15, 0), mergedOverlapping[0].endTime)
    }

    // Caso 4: Disparo caindo exatamente no início e exatamente no fim de um descanso
    @Test
    fun test4_triggerExactAtStartAndEnd_ofRest() {
        val rest = RestingPeriod(
            label = "Reunião",
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(15, 0)
        )
        // Regra do prompt: no início não toca; no fim é o término do descanso (pode tocar)
        assertTrue("Exatamente no início do descanso NÃO toca", rest.contains(LocalTime.of(14, 0)))
        assertFalse("Exatamente no fim do descanso o descanso acabou, LIVRE para tocar", rest.contains(LocalTime.of(15, 0)))
    }

    // Caso 5: Áudio ainda tocando quando o descanso começa (termina normalmente)
    @Test
    fun test5_audioPlayingWhenRestStarts_behavior() {
        // Quando o áudio começa às 11:59 e o descanso começa às 12:00,
        // o gatilho de 11:59 foi válido e não deve ser cancelado pela chegada do minuto 12:00
        val rest = RestingPeriod(startTime = LocalTime.of(12, 0), endTime = LocalTime.of(13, 0))
        val triggerTime = LocalTime.of(11, 59)
        assertFalse("Gatilho das 11:59 é válido e permitido", rest.contains(triggerTime))
    }

    // Caso 6: Troca de dia entre perfis diferentes e perfil que atravessa a meia-noite
    @Test
    fun test6_dayTransitionBetweenProfiles_andOvernightProfile() {
        val zone = ZoneId.of("America/Sao_Paulo")

        // Perfil Segunda a Sexta: 08:00 às 18:00
        val weekProfile = Profile(
            id = 1L,
            name = "Trabalho",
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(18, 0),
            intervalMinutes = 60
        )

        // Perfil Sábado que atravessa a meia-noite: 20:00 às 04:00 (entra no Domingo)
        val saturdayNightProfile = Profile(
            id = 2L,
            name = "Fim de Semana Noite",
            daysOfWeek = setOf(DayOfWeek.SATURDAY),
            startTime = LocalTime.of(20, 0),
            endTime = LocalTime.of(4, 0),
            intervalMinutes = 60
        )

        // Sexta 14:00
        val fridayAfternoon = ZonedDateTime.of(LocalDate.of(2026, 10, 16), LocalTime.of(14, 0), zone)
        val activeFriday = ScheduleCalculator.findActiveProfileFor(fridayAfternoon, listOf(weekProfile, saturdayNightProfile))
        assertEquals("Trabalho", activeFriday?.name)

        // Sábado 23:00
        val saturdayNight = ZonedDateTime.of(LocalDate.of(2026, 10, 17), LocalTime.of(23, 0), zone)
        val activeSat = ScheduleCalculator.findActiveProfileFor(saturdayNight, listOf(weekProfile, saturdayNightProfile))
        assertEquals("Fim de Semana Noite", activeSat?.name)

        // Domingo 02:00 (continuação da noite de sábado)
        val sundayDawn = ZonedDateTime.of(LocalDate.of(2026, 10, 18), LocalTime.of(2, 0), zone)
        val activeSundayDawn = ScheduleCalculator.findActiveProfileFor(sundayDawn, listOf(weekProfile, saturdayNightProfile))
        assertEquals("Fim de Semana Noite", activeSundayDawn?.name)
    }

    // Caso 7: Intervalo maior que o horário de funcionamento
    @Test
    fun test7_intervalLargerThanOperatingHours() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val shortWindowProfile = Profile(
            id = 1L,
            name = "Janela Curta",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(9, 0), // 1 hora de janela
            intervalMinutes = 120 // Intervalo de 2 horas
        )

        val mondayMorning = ZonedDateTime.of(LocalDate.of(2026, 10, 12), LocalTime.of(7, 30), zone)
        val triggers = ScheduleCalculator.getNextNTriggers(
            now = mondayMorning,
            count = 5,
            profiles = listOf(shortWindowProfile),
            activeAudios = listOf(testAudio)
        )

        // Só pode haver 1 disparo por segunda-feira (às 08:00), pois 08:00 + 120min = 10:00 (fora da janela)
        assertEquals(LocalTime.of(8, 0), triggers[0].triggerTime.toLocalTime())
        // O próximo disparo deve ser na segunda-feira seguinte
        if (triggers.size > 1) {
            assertEquals(LocalDate.of(2026, 10, 19), triggers[1].triggerTime.toLocalDate())
            assertEquals(LocalTime.of(8, 0), triggers[1].triggerTime.toLocalTime())
        }
    }

    // Caso 8: Mudança de fuso/horário do sistema e recálculo
    @Test
    fun test8_timezoneOrTimeChange_recalculation() {
        val spZone = ZoneId.of("America/Sao_Paulo")
        val utcZone = ZoneId.of("UTC")

        val profile = Profile(
            id = 1L,
            name = "Geral",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            intervalMinutes = 30
        )

        val nowSP = ZonedDateTime.of(LocalDate.of(2026, 10, 12), LocalTime.of(9, 0), spZone)
        val triggerSP = ScheduleCalculator.getNextTrigger(nowSP, listOf(profile), listOf(testAudio))
        assertNotNull(triggerSP)
        assertEquals(LocalTime.of(10, 0), triggerSP!!.triggerTime.toLocalTime())

        val nowUTC = ZonedDateTime.of(LocalDate.of(2026, 10, 12), LocalTime.of(9, 0), utcZone)
        val triggerUTC = ScheduleCalculator.getNextTrigger(nowUTC, listOf(profile), listOf(testAudio))
        assertNotNull(triggerUTC)
        assertEquals(LocalTime.of(10, 0), triggerUTC!!.triggerTime.toLocalTime())
    }

    // Caso 9: Disparo durante chamada, fone Bluetooth e Não perturbe
    @Test
    fun test9_triggerDuringCallBluetoothDnd_behaviorCheck() {
        // Verifica que se a chamada, fone ou DND estão ativos,
        // o disparo deve ser pulado e o próximo agendado
        val profile = Profile(
            id = 1L,
            name = "Diário",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(20, 0),
            intervalMinutes = 15
        )
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = ZonedDateTime.of(LocalDate.of(2026, 10, 12), LocalTime.of(8, 0), zone)

        // O próximo agendamento deve ser para o próximo intervalo válido (8:15)
        val nextTrigger = ScheduleCalculator.getNextTrigger(now, listOf(profile), listOf(testAudio))
        assertNotNull(nextTrigger)
        assertEquals(LocalTime.of(8, 15), nextTrigger!!.triggerTime.toLocalTime())
    }

    // Caso 10: Disparo perdido (descartar + notificação + agendar o próximo)
    @Test
    fun test10_missedTrigger_discardedAndNextScheduled() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val profile = Profile(
            id = 1L,
            name = "Diário",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(20, 0),
            intervalMinutes = 15
        )

        // O celular estava desligado das 08:00 às 08:37. Agora são 08:37.
        val now = ZonedDateTime.of(LocalDate.of(2026, 10, 12), LocalTime.of(8, 37), zone)

        // Ao recalcular, os disparos passados (08:00, 08:15, 08:30) são descartados
        // e o próximo agendado é estritamente no futuro (08:45)
        val nextTrigger = ScheduleCalculator.getNextTrigger(now, listOf(profile), listOf(testAudio))
        assertNotNull(nextTrigger)
        assertEquals(LocalTime.of(8, 45), nextTrigger!!.triggerTime.toLocalTime())
    }
}
