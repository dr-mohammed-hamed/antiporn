import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

val generateDnsFilterBin by tasks.registering {
    group = "hisn"
    description = "Generates app/src/main/assets/hisn_dns_filter.bin using 64-bit FNV-1a with 256-bucket offset table, bit-0 tagging, and open-source lists with offline fallback"

    doLast {
        val assetsDir = file("src/main/assets")
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }
        val outputFile = File(assetsDir, "hisn_dns_filter.bin")

        println("🔨 Generating Hisn Binary DNS Filter: ${outputFile.absolutePath}")

        fun fnv1a64(domain: String): Long {
            var hash = -3750763034362895579L // 0xcbf29ce484222325L
            val clean = domain.trim().lowercase()
            for (i in 0 until clean.length) {
                val b = clean[i].code.toLong() and 0xFF
                hash = hash xor b
                hash *= 1099511628211L // 0x100000001b3L
            }
            return hash
        }

        fun fetchDomains(urlStr: String, isHosts: Boolean): Set<String> {
            val result = HashSet<String>()
            try {
                println("🌐 Fetching blocklist from: $urlStr")
                val url = URI.create(urlStr).toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "HisnFilterCompiler/1.0")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
                                val domain = when {
                                    trimmed.startsWith("||") -> {
                                        trimmed.removePrefix("||").substringBefore('^').substringBefore('$').substringBefore('/')
                                    }
                                    isHosts -> {
                                        val parts = trimmed.split(Regex("\\s+"))
                                        if (parts.size >= 2) parts[1] else null
                                    }
                                    else -> {
                                        trimmed.substringBefore('#').substringBefore(';').trim()
                                    }
                                }

                                if (domain != null) {
                                    val clean = domain.trim().lowercase().removePrefix("www.")
                                    if (clean.isNotBlank() && clean.contains('.') && !clean.startsWith("localhost") && !clean.startsWith("0.0.0.0") && !clean.startsWith("127.0.0.1") && !clean.contains('*')) {
                                        result.add(clean)
                                    }
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                    println("   -> Downloaded ${result.size} domains successfully.")
                } else {
                    println("   ⚠️ HTTP ${conn.responseCode} returned, skipping.")
                }
            } catch (e: Exception) {
                println("   ⚠️ Failed fetching $urlStr: ${e.message} (Will use fallback)")
            }
            return result
        }

        fun pruneSubdomains(domains: Set<String>): Set<String> {
            val sorted = domains.sortedWith(compareBy({ it.count { c -> c == '.' } }, { it.length }))
            val rootSet = HashSet<String>(sorted.size)
            for (d in sorted) {
                var isSubdomain = false
                var dotIdx = d.indexOf('.')
                while (dotIdx != -1 && dotIdx < d.length - 1) {
                    val parent = d.substring(dotIdx + 1)
                    if (rootSet.contains(parent)) {
                        isSubdomain = true
                        break
                    }
                    dotIdx = d.indexOf('.', dotIdx + 1)
                }
                if (!isSubdomain) {
                    rootSet.add(d)
                }
            }
            return rootSet
        }

        // Comprehensive seed list of Adult Domains (Offline Fallback)
        val seedPornDomains = linkedSetOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "brazzers.com",
            "onlyfans.com", "chaturbate.com", "redtube.com", "youporn.com", "eporner.com",
            "stripchat.com", "hentai.tv", "nhentai.net", "erome.com", "fapello.com",
            "livejasmin.com", "bongacams.com", "cam4.com", "spankbang.com", "tnaflix.com",
            "drtuber.com", "tube8.com", "beeg.com", "slutload.com", "thumbzilla.com",
            "fuq.com", "motherless.com", "heavy-r.com", "extremetube.com", "porntrex.com",
            "pornone.com", "txxx.com", "vjav.com", "javlibrary.com", "rule34.xxx",
            "rule34.paheal.net", "e621.net", "danbooru.donmai.us", "gelbooru.com", "yande.re",
            "sankakucomplex.com", "hitomi.la", "luscious.net", "fakku.net", "tsumino.com",
            "pururin.io", "multporn.net", "comicporn.xxx", "8muses.com", "allporncomic.com",
            "manyvids.com", "clips4sale.com", "loyalfans.com", "fansly.com", "camwhores.tv",
            "webcamrips.com", "rec-tube.com", "camsoda.com", "flirt4free.com", "streamate.com",
            "myfreecams.com", "imlive.com", "jasmin.com", "bonga.com", "xlovecam.com",
            "stripchatgirls.com", "chaturbate.de", "pornhubpremium.com", "tube8.es", "xtube.com",
            "xcafe.com", "hqporner.com", "daftsex.com", "porndig.com", "freeones.com",
            "babes.com", "realitykings.com", "bangbros.com", "naughtyamerica.com", "mofos.com",
            "twistys.com", "digitalplayground.com", "evilangel.com", "wicked.com", "teamskeet.com",
            "kink.com", "milfed.com", "sislovesme.com", "familyhookups.com", "nubiles.net",
            "submissivex.com", "porndoe.com", "xbabe.com", "javdoe.com", "javhd.com",
            "javfull.net", "missav.com", "jable.tv", "netflav.com", "supjav.com",
            "7mmtv.tv", "avgle.com", "xhamsterlive.com", "xhamster.desi", "pornhub.org",
            "xvideos2.com", "xnxx2.com", "xnxx.tv", "xvideos.es", "porn.com", "sex.com",
            "xxx.com", "hentaihaven.xxx", "hanime.tv", "simply-hentai.com", "hentaicloud.com",
            "asmr.one", "iwara.tv", "submanga.io", "doujindesu.info", "komikdewasa.org",
            "hentaiera.com", "hentais.tube", "undergroundporn.tv", "shemale.xxx", "transporn.xxx",
            "gayporn.xxx", "manhub.com", "boyfriendtv.com", "cockyboys.com", "men.com",
            "nextdoorstudios.com", "lucasentertainment.com", "corbinfisher.com", "sean-cody.com",
            "belamionline.com", "masqueradeporn.com", "swingerporn.com", "adultwork.com",
            "escortforum.com", "vivastreet.com", "eroticmonkey.ch", "eurogirlsescort.com",
            "redhotpie.com.au", "seeking.com", "ashleymadison.com", "adultfriendfinder.com",
            "fetlife.com", "alt.com", "collarspace.com", "recon.com", "scruff.com",
            "grindr.com", "squirt.org", "sniffies.com", "adam4adam.com", "bgclive.com",
            "xhamster1.desi", "xhamster2.desi", "xhamster3.desi", "pornhub.net", "pornhub.com.de"
        )

        // Comprehensive seed list of Ad / Tracker / Telemetry Domains (Offline Fallback)
        val seedAdDomains = linkedSetOf(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com", "pagead2.googlesyndication.com", "adnxs.com",
            "criteo.com", "rubiconproject.com", "pubmatic.com", "openx.net",
            "taboola.com", "outbrain.com", "applovin.com", "unityads.unity3d.com",
            "adcolony.com", "vungle.com", "chartboost.com", "ironsrc.com",
            "supersonicads.com", "inmobi.com", "mopub.com", "tapjoy.com",
            "fyber.com", "smaato.net", "admob.com", "flurry.com",
            "appsflyer.com", "branch.io", "adjust.com", "kochava.com",
            "singular.net", "scorecardresearch.com", "quantserve.com", "moatads.com",
            "iasds.com", "bidswitch.net", "smartadserver.com", "casalemedia.com",
            "yieldmo.com", "triplelift.com", "sharethrough.com", "sovrn.com",
            "indexexchange.com", "adroll.com", "advertising.com", "popads.net",
            "popcash.net", "propellerads.com", "adcash.com", "trafficjunky.com",
            "trafficfactory.com", "exoclick.com", "juicyads.com", "ero-advertising.com",
            "plugrush.com", "clickadu.com", "hilltopads.com", "monetag.com",
            "adsterra.com", "adblade.com", "mgid.com", "revcontent.com",
            "content.ad", "adscale.de", "yieldlab.net", "teads.tv",
            "smartclip.net", "spotxchange.com", "spotx.tv", "tremorhub.com",
            "telaria.com", "unrulymedia.com", "exponential.com", "gumgum.com",
            "kargo.com", "undertone.com", "conversantmedia.com", "media.net",
            "chitika.com", "infolinks.com", "bidvertiser.com", "zergnet.com",
            "disqusads.com", "adzerk.net", "kevel.co", "flashtalking.com",
            "sizmek.com", "innovid.com", "liverail.com", "brightroll.com",
            "adtech.de", "adtechus.com", "burstnet.com", "contextweb.com"
        )

        val pornDomains = HashSet<String>(seedPornDomains)
        val adDomains = HashSet<String>(seedAdDomains)

        // Fetch live open-source lists
        val onlinePorn = fetchDomains("https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts", isHosts = true)
        val onlineAds = fetchDomains("https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt", isHosts = false)

        pornDomains.addAll(onlinePorn)
        adDomains.addAll(onlineAds)

        println("🧹 Deduplicating subdomains...")
        val prunedPorn = pruneSubdomains(pornDomains)
        val prunedAds = pruneSubdomains(adDomains)
        println("   -> Pruned Adult Domains: ${prunedPorn.size} (from ${pornDomains.size})")
        println("   -> Pruned Ad/Tracker Domains: ${prunedAds.size} (from ${adDomains.size})")

        // Map 63-bit key -> entry (Bit 0: 1L = Porn, 0L = Ads).
        // If conflict occurs, Porn (1L) strictly overrides Ads (0L).
        val entriesMap = java.util.HashMap<Long, Long>()

        for (domain in prunedPorn) {
            val clean = domain.trim().lowercase().removePrefix("www.")
            if (clean.isBlank()) continue
            val rawHash = fnv1a64(clean)
            val key = rawHash and -2L
            entriesMap[key] = key or 1L
        }

        for (domain in prunedAds) {
            val clean = domain.trim().lowercase().removePrefix("www.")
            if (clean.isBlank()) continue
            val rawHash = fnv1a64(clean)
            val key = rawHash and -2L
            if (!entriesMap.containsKey(key)) {
                entriesMap[key] = key // Bit 0 is 0L
            }
        }

        // Sort entries strictly in Unsigned 64-bit order (java.lang.Long.compareUnsigned)
        val sortedEntries = entriesMap.values.sortedWith { a, b ->
            java.lang.Long.compareUnsigned(a, b)
        }

        val totalCount = sortedEntries.size
        println("📊 Total Unique Domains Compiled: $totalCount (Porn: ${sortedEntries.count { (it and 1L) != 0L }}, Ads: ${sortedEntries.count { (it and 1L) == 0L }})")

        // Build the 256-bucket offset table (257 elements)
        val bucketOffsets = IntArray(257)
        var currentEntryIndex = 0

        for (b in 0..255) {
            bucketOffsets[b] = currentEntryIndex
            while (currentEntryIndex < totalCount) {
                val entry = sortedEntries[currentEntryIndex]
                val topByte = ((entry ushr 56) and 0xFF).toInt()
                if (topByte == b) {
                    currentEntryIndex++
                } else if (topByte > b) {
                    break
                } else {
                    throw IllegalStateException("Unsigned sorting invariant broken: topByte $topByte < bucket $b at index $currentEntryIndex")
                }
            }
        }
        bucketOffsets[256] = totalCount

        // Write binary file
        FileOutputStream(outputFile).use { fos ->
            DataOutputStream(fos).use { dos ->
                // 1. Magic bytes: "HISN"
                dos.write('H'.code)
                dos.write('I'.code)
                dos.write('S'.code)
                dos.write('N'.code)

                // 2. Version: 1
                dos.writeInt(1)

                // 3. Total Count: N
                dos.writeInt(totalCount)

                // 4. 256-bucket offset table: 257 Ints
                for (offset in bucketOffsets) {
                    dos.writeInt(offset)
                }

                // 5. Body: Sorted 64-bit Long entries
                for (entry in sortedEntries) {
                    dos.writeLong(entry)
                }
            }
        }

        val fileLength = outputFile.length()
        println("✅ Successfully generated ${outputFile.name}: $fileLength bytes (${String.format("%.2f", fileLength / 1024.0)} KB)")
    }
}
