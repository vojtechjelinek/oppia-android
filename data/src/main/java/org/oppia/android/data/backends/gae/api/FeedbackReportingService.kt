package org.oppia.android.data.backends.gae.api

import org.oppia.android.data.backends.gae.model.GaeFeedbackReport
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/** Service that posts feedback reports to remote storage. */
interface FeedbackReportingService {
  @POST("app_feedback_report/incoming_android_report")
  // TODO(#76): Update return payload for handling storage failures once retry policy is defined.
  fun postFeedbackReport(@Body report: GaeFeedbackReport): Call<Unit>
}
