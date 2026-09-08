package com.muzermat.harnessremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

/** Only ciphertext is stored in preferences; the wrapping key stays in Android Keystore. */
class Vault(context: Context) {
    private val prefs=context.getSharedPreferences("host-vault",Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("harness-vault",null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("harness-vault",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    @Synchronized fun read(): JSONObject {
        val raw=prefs.getString("data",null) ?: return JSONObject()
        val bytes=Base64.decode(raw,Base64.NO_WRAP)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }
    @Synchronized fun change(action: (JSONObject)->Unit) {
        val state=read();action(state)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
        val encoded=Base64.encodeToString(cipher.iv+cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        check(prefs.edit().putString("data",encoded).commit()) { "无法保存设备授权" }
    }
    fun hosts(): List<HostProfile> { val all=read().optJSONObject("hosts") ?: return emptyList();return all.keys().asSequence().map { HostProfile.fromJson(all.getJSONObject(it)) }.toList() }
    fun save(host: HostProfile) = change { s -> val all=s.optJSONObject("hosts") ?: JSONObject();all.put(host.id,host.json());s.put("hosts",all) }
    fun remove(id: String) = change { it.optJSONObject("hosts")?.remove(id);it.optJSONObject("cookies")?.remove(id) }
    fun cookie(id:String) = read().optJSONObject("cookies")?.optString(id,"") ?: ""
    fun cookie(id:String,value:String) = change { s -> val all=s.optJSONObject("cookies") ?: JSONObject();all.put(id,value);s.put("cookies",all) }
}
