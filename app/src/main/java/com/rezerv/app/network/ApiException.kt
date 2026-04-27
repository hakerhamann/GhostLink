package com.rezerv.app.network

class ApiException(
    val statusCode: Int,
    override val message: String
) : Exception(message)
