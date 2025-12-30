package com.example.parkingautorenew

data class ConfirmationDetails(
    val startTime: String,
    val expiryTime: String,
    val plate: String,
    val location: String,
    val confirmationNumber: String
)
