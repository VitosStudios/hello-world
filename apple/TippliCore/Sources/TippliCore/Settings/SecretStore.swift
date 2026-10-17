import Foundation
#if canImport(Security)
import Security
#endif

/// API keys in the Keychain (`apiKey.<providerId>`), optionally in a shared
/// access group (iOS app + keyboard extension).
public final class SecretStore {
    public let service: String
    public private(set) var accessGroup: String?
    private var memory: [String: String] = [:]
    private let lock = NSLock()

    /// - Parameter accessGroup: full keychain access group including the team
    ///   prefix (e.g. `ABCDE12345.ch.vitosstudios.tippli.shared`), or nil.
    public init(service: String = "Tippli", accessGroup: String? = nil) {
        self.service = service
        if let g = accessGroup, !g.isEmpty, !g.contains("$(") {
            self.accessGroup = g
        } else {
            self.accessGroup = nil
        }
    }

    /// The keychain group from Info.plist (`TippliKeychainGroup`), if it has a team prefix.
    public static var bundleAccessGroup: String? {
        guard let g = Bundle.main.object(forInfoDictionaryKey: "TippliKeychainGroup") as? String,
              !g.isEmpty, !g.contains("$(") else { return nil }
        // Without a development team the prefix is missing ("ch.vitosstudios…");
        // such a group cannot work, so fall back to the app's default group.
        let first = g.split(separator: ".").first.map(String.init) ?? ""
        let looksLikeTeam = first.count == 10 && first.uppercased() == first
        return looksLikeTeam ? g : nil
    }

    public func apiKey(for providerId: String) -> String {
        get(SettingKey.apiKey(providerId)) ?? ""
    }

    public func setAPIKey(_ key: String?, for providerId: String) {
        let trimmed = key?.trimmingCharacters(in: .whitespacesAndNewlines)
        set((trimmed?.isEmpty ?? true) ? nil : trimmed, for: SettingKey.apiKey(providerId))
    }

    public func hasAPIKey(for providerId: String) -> Bool {
        !apiKey(for: providerId).isEmpty
    }

    #if canImport(Security)

    private func baseQuery(_ account: String, group: String?) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        if let g = group {
            q[kSecAttrAccessGroup as String] = g
        }
        return q
    }

    public func get(_ account: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        var q = baseQuery(account, group: accessGroup)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        var status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecMissingEntitlement && accessGroup != nil {
            accessGroup = nil
            q[kSecAttrAccessGroup as String] = nil
            status = SecItemCopyMatching(q as CFDictionary, &result)
        }
        guard status == errSecSuccess, let data = result as? Data else {
            return memory[account]
        }
        return String(data: data, encoding: .utf8)
    }

    public func set(_ value: String?, for account: String) {
        lock.lock()
        defer { lock.unlock() }
        var status = write(value, account: account, group: accessGroup)
        if status == errSecMissingEntitlement && accessGroup != nil {
            accessGroup = nil
            status = write(value, account: account, group: nil)
        }
        if status != errSecSuccess && status != errSecItemNotFound {
            // Keychain unavailable (e.g. unsigned simulator build): keep it for this process.
            if let v = value { memory[account] = v } else { memory[account] = nil }
        } else {
            memory[account] = nil
        }
    }

    private func write(_ value: String?, account: String, group: String?) -> OSStatus {
        let q = baseQuery(account, group: group)
        let deleteStatus = SecItemDelete(q as CFDictionary)
        if deleteStatus == errSecMissingEntitlement { return deleteStatus }
        guard let v = value, let data = v.data(using: .utf8) else {
            return errSecSuccess
        }
        var add = q
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil)
    }

    #else

    public func get(_ account: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return memory[account]
    }

    public func set(_ value: String?, for account: String) {
        lock.lock()
        defer { lock.unlock() }
        memory[account] = value
    }

    #endif
}
