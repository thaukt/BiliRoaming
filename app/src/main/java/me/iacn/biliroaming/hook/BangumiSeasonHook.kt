package me.iacn.biliroaming.hook

import android.util.ArrayMap
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers.*
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.Constant.TAG
import me.iacn.biliroaming.Constant.TYPE_EPISODE_ID
import me.iacn.biliroaming.Constant.TYPE_SEASON_ID
import me.iacn.biliroaming.XposedInit
import me.iacn.biliroaming.XposedInit.Companion.toastMessage
import me.iacn.biliroaming.network.BiliRoamingApi.getSeason
import me.iacn.biliroaming.network.BiliRoamingApi.seasonBp
import org.json.JSONObject
import java.io.IOException

/**
 * Created by iAcn on 2019/3/27
 * Email i@iacn.me
 */
class BangumiSeasonHook(classLoader: ClassLoader?) : BaseHook(classLoader!!) {
    private val lastSeasonInfo: MutableMap<String, Any?>
    override fun startHook() {
        if (!XposedInit.sPrefs.getBoolean("main_func", false)) return
        Log.d(TAG, "startHook: BangumiSeason")
        val paramsMapClass = findClass("com.bilibili.bangumi.data.page.detail." +
                "BangumiDetailApiService\$UniformSeasonParamsMap", mClassLoader)
        XposedBridge.hookAllConstructors(paramsMapClass, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun afterHookedMethod(param: MethodHookParam) {
                val paramMap: Map<String, String> = param.thisObject as Map<String, String>
                val type = param.args[1] as Int
                when (type) {
                    TYPE_SEASON_ID -> {
                        val seasonId = paramMap["season_id"]
                        lastSeasonInfo["season_id"] = seasonId
                        Log.d(TAG, "SeasonInformation: seasonId = $seasonId")
                    }
                    TYPE_EPISODE_ID -> {
                        val episodeId = paramMap["ep_id"]
                        lastSeasonInfo["episode_id"] = episodeId
                        Log.d(TAG, "SeasonInformation: episodeId = $episodeId")
                    }
                    else -> return
                }
                lastSeasonInfo["type"] = type
                val accessKey = paramMap["access_key"]
                lastSeasonInfo["access_key"] = accessKey
            }
        })
        val responseClass = findClass(instance!!.retrofitResponse(), mClassLoader)
        XposedBridge.hookAllConstructors(responseClass, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val body = param.args[1]
                val bangumiApiResponse = instance!!.bangumiApiResponse()

                // Filter non-bangumi responses
                // If it isn't bangumi, the type variable will not exist in this map
                if (!bangumiApiResponse!!.isInstance(body) || !lastSeasonInfo.containsKey("type")) return
                val result = getObjectField(body, "result")
                // Filter normal bangumi and other responses
                if (isBangumiWithWatchPermission(getIntField(body, "code"), result)) {
                    if (result != null) {
                        val bangumiSeasonClass = instance!!.bangumiUniformSeason()
                        if (bangumiSeasonClass!!.isInstance(result) && XposedInit.sPrefs.getBoolean("allow_download", false)) {
                            val rights = getObjectField(result, "rights")
                            setBooleanField(rights, "allowDownload", true)
                            Log.d(TAG, "Download allowed")
                            toastMessage("已允许下载")
                        }
                    }
                    lastSeasonInfo.clear()
                    return
                }
                val useCache = result != null
                val content: String?
                content = if (XposedInit.sPrefs.getBoolean("use_biliplus", false) && result != null) seasonBp(getObjectField(result, "seasonId") as String) else getSeasonInfoFromProxyServer(useCache)
                val contentJson = JSONObject(content!!)
                val code = contentJson.optInt("code")

                val fastJsonClass = instance!!.fastJson()
                val beanClass = instance!!.bangumiUniformSeason()
                val resultJson = contentJson.optJSONObject("result")
                val newResult = callStaticMethod(fastJsonClass,
                        instance!!.fastJsonParse(), resultJson!!.toString(), beanClass)

                Log.d(TAG, "Got new season information from proxy server: code = " + code
                        + ", useCache = " + useCache)
                if (!isBangumiWithWatchPermission(code, newResult)) {
                    toastMessage("解锁失败，请重试或启用Biliplus")
                    lastSeasonInfo.clear()
                    return
                }
                toastMessage("已从代理服务器获取番剧信息")
                if (code == 0) {
                    val newRights = getObjectField(newResult, "rights")
                    if (XposedInit.sPrefs.getBoolean("allow_download", false))
                        setBooleanField(newRights, "allowDownload", true)
                    if (useCache) {
                        // Replace only episodes and rights
                        // Remain user information, such as follow status, watch progress, etc.
                        if (!getBooleanField(newRights, "areaLimit")) {
                            val newEpisodes = getObjectField(newResult, "episodes")
                            var newModules: Any? = null
                            findFieldIfExists(newResult.javaClass, "modules")?.let {
                                newModules = getObjectField(newResult, "modules")
                            }
                            setObjectField(result, "rights", newRights)
                            setObjectField(result, "episodes", newEpisodes)
                            setObjectField(result, "seasonLimit", null)
                            findFieldIfExists(result.javaClass, "modules")?.let {
                                newModules?.let { it2 ->
                                    setObjectField(result, it.name, it2)
                                }
                            }
                        }
                    } else {
                        setIntField(body, "code", 0)
                        setObjectField(body, "result", newResult)
                    }
                }
                lastSeasonInfo.clear()
            }
        })
    }

    private fun isBangumiWithWatchPermission(code: Int, result: Any?): Boolean {
        Log.d(TAG, "BangumiApiResponse: code = $code, result = $result")
        toastMessage("发现版权番剧，尝试解锁……")
        if (result != null) {
            val bangumiSeasonClass = instance!!.bangumiUniformSeason()
            if (bangumiSeasonClass!!.isInstance(result)) {
                val rights = getObjectField(result, "rights")
                val areaLimit = getBooleanField(rights, "areaLimit")
                return !areaLimit
            }
        }
        return code != -404
    }

    @Throws(IOException::class)
    private fun getSeasonInfoFromProxyServer(useCache: Boolean): String? {
        Log.d(TAG, "Limited Bangumi: seasonInfo = $lastSeasonInfo")
        var id: String? = null
        val accessKey = lastSeasonInfo["access_key"] as String?
        when (lastSeasonInfo["type"] as Int) {
            TYPE_SEASON_ID -> id = lastSeasonInfo["season_id"] as String?
            TYPE_EPISODE_ID -> id = "ep" + lastSeasonInfo["episode_id"]
        }
        return getSeason(id, accessKey, useCache)
    }

    init {
        lastSeasonInfo = ArrayMap()
    }
}