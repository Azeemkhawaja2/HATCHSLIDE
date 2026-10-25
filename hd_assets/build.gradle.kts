plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("hd_assets")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
