package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate

@WebMvcTest(RescueAdminController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class RescueAdminControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @MockitoBean private lateinit var previewService: RescuePreviewService
    @MockitoBean private lateinit var adminService: RescueAdminService

    private val admin = UsernamePasswordAuthenticationToken("ali-cli", null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
    private val regularUser = UsernamePasswordAuthenticationToken("user", null, emptyList())

    private fun previewView(id: Long) = RescuePreviewView(
        id = id, userId = 3L, createdAt = Instant.parse("2026-10-13T10:00:00Z"), promptVersion = "comeback-rescue.v1",
        source = RescueSource.AI, angle = "rematch", title = "Gewitter, one more try", body = "8 words, about 3 min.",
        outroWin = null, outroSoft = null, wordIds = listOf(1L, 2L),
        candidates = listOf(
            RescueCandidateView(0, "rematch", "Gewitter, one more try", "8 words", listOf("F1"), 1, emptyList(), true, CopyRating.UP, null),
        ),
    )

    private fun preview(id: Long) = RescuePreview(
        id = id, userId = 3L, promptVersion = "comeback-rescue.v1", source = RescueSource.AI, angle = "rematch",
        title = "t", body = "b", wordIds = "[1,2]",
    )

    @Test
    fun `rejects non-admin callers`() {
        mockMvc.post("/admin/rescue/previews?count=3") { with(authentication(regularUser)) }
            .andExpect { status { isForbidden() } }
        mockMvc.get("/admin/rescue/taste") { with(authentication(regularUser)) }
            .andExpect { status { isForbidden() } }

        verify(previewService, never()).generate(anyInt())
    }

    @Test
    fun `generates previews and returns them with their candidates`() {
        `when`(previewService.generate(3)).thenReturn(listOf(preview(11L)))
        `when`(adminService.previews(1)).thenReturn(listOf(previewView(11L)))

        mockMvc.post("/admin/rescue/previews?count=3") { with(authentication(admin)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data[0].id") { value(11) }
                jsonPath("$.data[0].candidates[0].chosen") { value(true) }
                jsonPath("$.data[0].candidates[0].rating") { value("UP") }
            }
    }

    @Test
    fun `rating a candidate returns the updated taste score`() {
        `when`(previewService.tasteScore()).thenReturn(TasteScore(rated = 4, up = 3, allRated = 9, allUp = 5))

        mockMvc.post("/admin/rescue/previews/11/ratings") {
            with(authentication(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"candidateIndex":1,"rating":"UP","note":"warm"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.upRate") { value(0.75) }
            jsonPath("$.data.target") { value(0.8) }
        }

        verify(previewService).rate(11L, 1, CopyRating.UP, "warm")
    }

    @Test
    fun `rating an unknown preview is not found`() {
        `when`(previewService.rate(anyLong(), anyInt(), anyRating(), anyNote()))
            .thenThrow(NoSuchElementException("Preview not found"))

        mockMvc.post("/admin/rescue/previews/99/ratings") {
            with(authentication(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"candidateIndex":0,"rating":"DOWN"}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `rating a candidate that does not exist is a bad request`() {
        `when`(previewService.rate(anyLong(), anyInt(), anyRating(), anyNote()))
            .thenThrow(IllegalArgumentException("No candidate 5 in this preview"))

        mockMvc.post("/admin/rescue/previews/11/ratings") {
            with(authentication(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"candidateIndex":5,"rating":"UP"}"""
        }.andExpect { status { isBadRequest() } }

        mockMvc.post("/admin/rescue/previews/11/ratings") {
            with(authentication(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"candidateIndex":-1,"rating":"UP"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `lists plans and the funnel`() {
        `when`(adminService.plans(50)).thenReturn(
            listOf(
                RescuePlanView(
                    7L, 3L, LocalDate.of(2026, 10, 13), RescueStatus.HOLDOUT, RescueSource.FALLBACK, "rematch",
                    "t", "b", null, null, 8, null, null,
                )
            )
        )
        `when`(adminService.funnel(14)).thenReturn(
            listOf(RescueFunnelRow(RescueArm.SENT, RescueSource.AI, "rematch", 5, 5, 2, 1, 3))
        )

        mockMvc.get("/admin/rescue/plans") { with(authentication(admin)) }
            .andExpect { jsonPath("$.data[0].status") { value("HOLDOUT") } }
        mockMvc.get("/admin/rescue/funnel") { with(authentication(admin)) }
            .andExpect { jsonPath("$.data[0].returned24h") { value(3) } }
    }

    @Test
    fun `exports rated candidates as json lines`() {
        `when`(adminService.evalSet()).thenReturn(
            listOf(
                RescueEvalLine(11L, 0, "comeback-rescue.v1", CopyRating.UP, null, true, "rematch", "a", "b", listOf("F1"), emptyList()),
                RescueEvalLine(11L, 1, "comeback-rescue.v1", CopyRating.DOWN, "flat", false, "curiosity", "c", "d", emptyList(), emptyList()),
            )
        )

        mockMvc.get("/admin/rescue/eval.jsonl") { with(authentication(admin)) }
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.TEXT_PLAIN) }
                content { string(containsString("\"rating\":\"DOWN\"")) }
            }
    }

    private fun anyRating(): CopyRating = any(CopyRating::class.java) ?: CopyRating.UP

    private fun anyNote(): String? = any()
}
