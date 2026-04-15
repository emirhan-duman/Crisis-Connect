//
//  Profile.swift
//  Crisis Connect
//
//  Created by Assistant on 09.12.2025
//

import Foundation
import SwiftData

@Model
final class Profile {
    var fullName: String
    var phone: String
    var email: String
    var bio: String
    var avatarImageData: Data?
    var createdAt: Date

    init(fullName: String = "",
         phone: String = "",
         email: String = "",
         bio: String = "",
         avatarImageData: Data? = nil,
         createdAt: Date = .now) {
        self.fullName = fullName
        self.phone = phone
        self.email = email
        self.bio = bio
        self.avatarImageData = avatarImageData
        self.createdAt = createdAt
    }
}
