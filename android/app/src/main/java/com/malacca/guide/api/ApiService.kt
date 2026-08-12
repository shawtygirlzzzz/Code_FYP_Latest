package com.malacca.guide.api

import com.malacca.guide.api.models.AnalyzeResponse
import com.malacca.guide.api.models.HealthResponse
import com.malacca.guide.api.models.NearbyRequest
import com.malacca.guide.api.models.NearbyResponse
import com.malacca.guide.api.models.RestaurantResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {

    @GET("health")
    suspend fun health(): Response<HealthResponse>

    /**
     * @param audio optional WAV of the spoken question. When present the backend
     *   uses it instead of [query]; Gemini reads the audio directly.
     */
    @Multipart
    @POST("analyze")
    suspend fun analyze(
        @Part image: MultipartBody.Part,
        @Part("query") query: RequestBody,
        @Part("language") language: RequestBody,
        @Part("landmark_context") landmarkContext: RequestBody,
        @Part audio: MultipartBody.Part? = null
    ): Response<AnalyzeResponse>

    @Multipart
    @POST("restaurant")
    suspend fun analyzeRestaurant(
        @Part image: MultipartBody.Part,
        @Part("lat") lat: RequestBody,
        @Part("lng") lng: RequestBody,
        @Part("language") language: RequestBody
    ): Response<RestaurantResponse>

    @POST("restaurant/nearby")
    suspend fun nearbyRestaurants(
        @Body request: NearbyRequest
    ): Response<NearbyResponse>
}
