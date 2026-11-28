package pro.sitescope.app.net

/** Public speed-test endpoints published by the providers and the LibreSpeed project. */
internal object SpeedServers {
    // https://github.com/librespeed/speedtest/blob/master/server-list.json
    private fun libre(id: String, provider: String, place: String, backend: String) = SpeedEngine.Server(
        id, provider, place, "$backend/garbage.php?ckSize=%d", "$backend/empty.php",
        pingEndpoint = "$backend/empty.php", downloadInMiB = true, binaryDownload = true
    )

    private fun file(id: String, provider: String, place: String, url: String) =
        SpeedEngine.Server(id, provider, place, url, "", binaryDownload = true)

    val public = listOf(
        SpeedEngine.Server("cf", "Cloudflare", "Ближайший узел Anycast", "https://speed.cloudflare.com/__down?bytes=%d",
            "https://speed.cloudflare.com/__up", pingEndpoint = "https://speed.cloudflare.com/__down?bytes=0", binaryDownload = true),
        libre("clouvider-ams", "Clouvider", "Амстердам, Нидерланды", "https://ams.speedtest.clouvider.net/backend"),
        libre("clouvider-lon", "Clouvider", "Лондон, Великобритания", "https://lon.speedtest.clouvider.net/backend"),
        libre("clouvider-nyc", "Clouvider", "Нью-Йорк, США", "https://nyc.speedtest.clouvider.net/backend"),
        libre("clouvider-atl", "Clouvider", "Атланта, США", "https://atl.speedtest.clouvider.net/backend"),
        libre("clouvider-la", "Clouvider", "Лос-Анджелес, США", "https://la.speedtest.clouvider.net/backend"),
        libre("sharktech-ams", "Sharktech", "Амстердам, Нидерланды", "https://amsspeed.sharktech.net/backend"),
        libre("sharktech-chi", "Sharktech", "Чикаго, США", "https://chispeed.sharktech.net/backend"),
        libre("sharktech-den", "Sharktech", "Денвер, США", "https://denspeed.sharktech.net/backend"),
        libre("sharktech-las", "Sharktech", "Лас-Вегас, США", "https://lasspeed.sharktech.net/backend"),
        libre("sharktech-lax", "Sharktech", "Лос-Анджелес, США", "https://laxspeed.sharktech.net/backend"),
        libre("cesnet-prg", "CESNET", "Прага, Чехия", "https://speedtest.cesnet.cz/backend"),
        libre("turris-prg", "Turris", "Прага, Чехия", "https://librespeed.turris.cz/backend"),
        libre("garr-roma", "Consortium GARR", "Рим, Италия", "https://st-be-rm2.infra.garr.it"),
        libre("a573-tokyo", "A573", "Токио, Япония", "https://librespeed.a573.net/backend"),
        file("selectel", "Selectel", "Россия", "https://speedtest.selectel.ru/100MB")
    )

    val custom = SpeedEngine.Server("custom", "Свой сервер", "Адреса из настроек", "", "")
}
