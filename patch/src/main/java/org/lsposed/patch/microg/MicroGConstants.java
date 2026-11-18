/*
 * Copyright (C) 2026 LSPatch Contributors
 *
 * Original code is credited to Morphe:
 * https://github.com/MorpheApp/morphe-patches/blob/main/patches/src/main/kotlin/app/morphe/patches/shared/misc/gms/GmsCoreSupportPatch.kt
 * and hoo-dles:
 * https://github.com/hoo-dles/morphe-patches/blob/main/patches/src/main/kotlin/hoodles/morphe/patches/all/microg/Constants.kt
 *
 * Licensed under the GNU General Public License v3.0.
 */

package org.lsposed.patch.microg;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A collection of permissions, intents and content provider authorities
 * that are present in GmsCore which need to be transformed for MicroG integration.
 */
public final class MicroGConstants {

    private MicroGConstants() {}

    public static final Set<String> CAPABILITIES;
    public static final Set<String> PERMISSIONS;
    public static final Set<String> ACTIONS;
    public static final Set<String> AUTHORITIES;

    static {
        Set<String> capabilities = new HashSet<>();
        capabilities.add("com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL");
        CAPABILITIES = Collections.unmodifiableSet(capabilities);

        Set<String> permissions = new HashSet<>();
        permissions.add("com.google.android.c2dm.permission.RECEIVE");
        permissions.add("com.google.android.c2dm.permission.SEND");
        permissions.add("com.google.android.gms.auth.api.phone.permission.SEND");
        permissions.add("com.google.android.gms.permission.AD_ID");
        permissions.add("com.google.android.gms.permission.AD_ID_NOTIFICATION");
        permissions.add("com.google.android.gms.permission.CAR_FUEL");
        permissions.add("com.google.android.gms.permission.CAR_INFORMATION");
        permissions.add("com.google.android.gms.permission.CAR_MILEAGE");
        permissions.add("com.google.android.gms.permission.CAR_SPEED");
        permissions.add("com.google.android.gms.permission.CAR_VENDOR_EXTENSION");
        permissions.add("com.google.android.googleapps.permission.GOOGLE_AUTH");
        permissions.add("com.google.android.googleapps.permission.GOOGLE_AUTH.cp");
        permissions.add("com.google.android.googleapps.permission.GOOGLE_AUTH.local");
        permissions.add("com.google.android.googleapps.permission.GOOGLE_AUTH.mail");
        permissions.add("com.google.android.googleapps.permission.GOOGLE_AUTH.writely");
        permissions.add("com.google.android.gtalkservice.permission.GTALK_SERVICE");
        permissions.add("com.google.android.providers.gsf.permission.READ_GSERVICES");
        PERMISSIONS = Collections.unmodifiableSet(permissions);

        Set<String> actions = new HashSet<>();
        actions.add("com.google.android.c2dm.intent.RECEIVE");
        actions.add("com.google.android.c2dm.intent.REGISTER");
        actions.add("com.google.android.c2dm.intent.REGISTRATION");
        actions.add("com.google.android.c2dm.intent.UNREGISTER");
        actions.add("com.google.android.contextmanager.service.ContextManagerService.START");
        actions.add("com.google.android.gcm.intent.SEND");
        actions.add("com.google.android.gms.accounts.ACCOUNT_SERVICE");
        actions.add("com.google.android.gms.accountsettings.ACCOUNT_PREFERENCES_SETTINGS");
        actions.add("com.google.android.gms.accountsettings.action.BROWSE_SETTINGS");
        actions.add("com.google.android.gms.accountsettings.action.VIEW_SETTINGS");
        actions.add("com.google.android.gms.accountsettings.MY_ACCOUNT");
        actions.add("com.google.android.gms.accountsettings.PRIVACY_SETTINGS");
        actions.add("com.google.android.gms.accountsettings.SECURITY_SETTINGS");
        actions.add("com.google.android.gms.ads.gservice.START");
        actions.add("com.google.android.gms.ads.identifier.service.EVENT_ATTESTATION");
        actions.add("com.google.android.gms.ads.service.CACHE");
        actions.add("com.google.android.gms.ads.service.CONSENT_LOOKUP");
        actions.add("com.google.android.gms.ads.service.HTTP");
        actions.add("com.google.android.gms.analytics.service.START");
        actions.add("com.google.android.gms.app.settings.GoogleSettingsLink");
        actions.add("com.google.android.gms.appstate.service.START");
        actions.add("com.google.android.gms.appusage.service.START");
        actions.add("com.google.android.gms.asterism.service.START");
        actions.add("com.google.android.gms.audiomodem.service.AudioModemService.START");
        actions.add("com.google.android.gms.audit.service.START");
        actions.add("com.google.android.gms.auth.account.authapi.START");
        actions.add("com.google.android.gms.auth.account.authenticator.auto.service.START");
        actions.add("com.google.android.gms.auth.account.authenticator.chromeos.START");
        actions.add("com.google.android.gms.auth.account.authenticator.tv.service.START");
        actions.add("com.google.android.gms.auth.account.data.service.START");
        actions.add("com.google.android.gms.auth.api.credentials.PICKER");
        actions.add("com.google.android.gms.auth.api.credentials.service.START");
        actions.add("com.google.android.gms.auth.api.identity.service.authorization.START");
        actions.add("com.google.android.gms.auth.api.identity.service.credentialsaving.START");
        actions.add("com.google.android.gms.auth.api.identity.service.signin.START");
        actions.add("com.google.android.gms.auth.api.phone.service.InternalService.START");
        actions.add("com.google.android.gms.auth.api.signin.service.START");
        actions.add("com.google.android.gms.auth.be.appcert.AppCertService");
        actions.add("com.google.android.gms.auth.blockstore.service.START");
        actions.add("com.google.android.gms.auth.config.service.START");
        actions.add("com.google.android.gms.auth.cryptauth.cryptauthservice.START");
        actions.add("com.google.android.gms.auth.GOOGLE_SIGN_IN");
        actions.add("com.google.android.gms.auth.login.LOGIN");
        actions.add("com.google.android.gms.auth.proximity.devicesyncservice.START");
        actions.add("com.google.android.gms.auth.proximity.securechannelservice.START");
        actions.add("com.google.android.gms.auth.proximity.START");
        actions.add("com.google.android.gms.auth.service.START");
        actions.add("com.google.android.gms.backup.ACTION_BACKUP_SETTINGS");
        actions.add("com.google.android.gms.backup.G1_BACKUP");
        actions.add("com.google.android.gms.backup.G1_RESTORE");
        actions.add("com.google.android.gms.backup.GMS_MODULE_RESTORE");
        actions.add("com.google.android.gms.beacon.internal.IBleService.START");
        actions.add("com.google.android.gms.car.service.START");
        actions.add("com.google.android.gms.carrierauth.service.START");
        actions.add("com.google.android.gms.cast.firstparty.START");
        actions.add("com.google.android.gms.cast.remote_display.service.START");
        actions.add("com.google.android.gms.cast.service.BIND_CAST_DEVICE_CONTROLLER_SERVICE");
        actions.add("com.google.android.gms.cast_mirroring.service.START");
        actions.add("com.google.android.gms.checkin.BIND_TO_SERVICE");
        actions.add("com.google.android.gms.chromesync.service.START");
        actions.add("com.google.android.gms.clearcut.service.START");
        actions.add("com.google.android.gms.common.account.CHOOSE_ACCOUNT");
        actions.add("com.google.android.gms.common.download.START");
        actions.add("com.google.android.gms.common.service.START");
        actions.add("com.google.android.gms.common.telemetry.service.START");
        actions.add("com.google.android.gms.config.START");
        actions.add("com.google.android.gms.constellation.service.START");
        actions.add("com.google.android.gms.credential.manager.service.firstparty.START");
        actions.add("com.google.android.gms.deviceconnection.service.START");
        actions.add("com.google.android.gms.drive.ApiService.RESET_AFTER_BOOT");
        actions.add("com.google.android.gms.drive.ApiService.START");
        actions.add("com.google.android.gms.drive.ApiService.STOP");
        actions.add("com.google.android.gms.droidguard.service.INIT");
        actions.add("com.google.android.gms.droidguard.service.PING");
        actions.add("com.google.android.gms.droidguard.service.START");
        actions.add("com.google.android.gms.enterprise.loader.service.START");
        actions.add("com.google.android.gms.facs.cache.service.START");
        actions.add("com.google.android.gms.facs.internal.service.START");
        actions.add("com.google.android.gms.feedback.internal.IFeedbackService");
        actions.add("com.google.android.gms.fido.credentialstore.internal_service.START");
        actions.add("com.google.android.gms.fido.fido2.privileged.START");
        actions.add("com.google.android.gms.fido.fido2.regular.START");
        actions.add("com.google.android.gms.fido.fido2.zeroparty.START");
        actions.add("com.google.android.gms.fido.sourcedevice.service.START");
        actions.add("com.google.android.gms.fido.targetdevice.internal_service.START");
        actions.add("com.google.android.gms.fido.u2f.privileged.START");
        actions.add("com.google.android.gms.fido.u2f.thirdparty.START");
        actions.add("com.google.android.gms.fido.u2f.zeroparty.START");
        actions.add("com.google.android.gms.fitness.BleApi");
        actions.add("com.google.android.gms.fitness.ConfigApi");
        actions.add("com.google.android.gms.fitness.GoalsApi");
        actions.add("com.google.android.gms.fitness.GoogleFitnessService.START");
        actions.add("com.google.android.gms.fitness.HistoryApi");
        actions.add("com.google.android.gms.fitness.InternalApi");
        actions.add("com.google.android.gms.fitness.RecordingApi");
        actions.add("com.google.android.gms.fitness.SensorsApi");
        actions.add("com.google.android.gms.fitness.SessionsApi");
        actions.add("com.google.android.gms.fonts.service.START");
        actions.add("com.google.android.gms.freighter.service.START");
        actions.add("com.google.android.gms.games.internal.connect.service.START");
        actions.add("com.google.android.gms.games.PLAY_GAMES_UPGRADE");
        actions.add("com.google.android.gms.games.service.START");
        actions.add("com.google.android.gms.gass.START");
        actions.add("com.google.android.gms.gmscompliance.service.START");
        actions.add("com.google.android.gms.googlehelp.HELP");
        actions.add("com.google.android.gms.googlehelp.service.GoogleHelpService.START");
        actions.add("com.google.android.gms.growth.service.START");
        actions.add("com.google.android.gms.herrevad.services.LightweightNetworkQualityAndroidService.START");
        actions.add("com.google.android.gms.icing.INDEX_SERVICE");
        actions.add("com.google.android.gms.icing.LIGHTWEIGHT_INDEX_SERVICE");
        actions.add("com.google.android.gms.identity.service.BIND");
        actions.add("com.google.android.gms.inappreach.service.START");
        actions.add("com.google.android.gms.instantapps.START");
        actions.add("com.google.android.gms.kids.service.START");
        actions.add("com.google.android.gms.languageprofile.service.START");
        actions.add("com.google.android.gms.learning.internal.dynamitesupport.START");
        actions.add("com.google.android.gms.learning.intservice.START");
        actions.add("com.google.android.gms.learning.predictor.START");
        actions.add("com.google.android.gms.learning.trainer.START");
        actions.add("com.google.android.gms.learning.training.background.START");
        actions.add("com.google.android.gms.location.places.GeoDataApi");
        actions.add("com.google.android.gms.location.places.PlaceDetectionApi");
        actions.add("com.google.android.gms.location.places.PlacesApi");
        actions.add("com.google.android.gms.location.reporting.service.START");
        actions.add("com.google.android.gms.location.settings.LOCATION_HISTORY");
        actions.add("com.google.android.gms.location.settings.LOCATION_REPORTING_SETTINGS");
        actions.add("com.google.android.gms.locationsharing.api.START");
        actions.add("com.google.android.gms.locationsharingreporter.service.START");
        actions.add("com.google.android.gms.lockbox.service.START");
        actions.add("com.google.android.gms.matchstick.lighter.service.START");
        actions.add("com.google.android.gms.mdm.services.DeviceManagerApiService.START");
        actions.add("com.google.android.gms.mdm.services.START");
        actions.add("com.google.android.gms.mdns.service.START");
        actions.add("com.google.android.gms.measurement.START");
        actions.add("com.google.android.gms.nearby.bootstrap.service.NearbyBootstrapService.START");
        actions.add("com.google.android.gms.nearby.connection.service.START");
        actions.add("com.google.android.gms.nearby.fastpair.START");
        actions.add("com.google.android.gms.nearby.messages.service.NearbyMessagesService.START");
        actions.add("com.google.android.gms.nearby.sharing.service.NearbySharingService.START");
        actions.add("com.google.android.gms.nearby.sharing.START_SERVICE");
        actions.add("com.google.android.gms.notifications.service.START");
        actions.add("com.google.android.gms.ocr.service.internal.START");
        actions.add("com.google.android.gms.ocr.service.START");
        actions.add("com.google.android.gms.oss.licenses.service.START");
        actions.add("com.google.android.gms.payse.service.BIND");
        actions.add("com.google.android.gms.people.contactssync.service.START");
        actions.add("com.google.android.gms.people.service.START");
        actions.add("com.google.android.gms.phenotype.service.START");
        actions.add("com.google.android.gms.photos.autobackup.service.START");
        actions.add("com.google.android.gms.playlog.service.START");
        actions.add("com.google.android.gms.plus.service.default.INTENT");
        actions.add("com.google.android.gms.plus.service.image.INTENT");
        actions.add("com.google.android.gms.plus.service.internal.START");
        actions.add("com.google.android.gms.plus.service.START");
        actions.add("com.google.android.gms.potokens.service.START");
        actions.add("com.google.android.gms.pseudonymous.service.START");
        actions.add("com.google.android.gms.rcs.START");
        actions.add("com.google.android.gms.reminders.service.START");
        actions.add("com.google.android.gms.romanesco.MODULE_BACKUP_AGENT");
        actions.add("com.google.android.gms.romanesco.service.START");
        actions.add("com.google.android.gms.safetynet.service.START");
        actions.add("com.google.android.gms.scheduler.ACTION_PROXY_SCHEDULE");
        actions.add("com.google.android.gms.search.service.SEARCH_AUTH_START");
        actions.add("com.google.android.gms.semanticlocation.service.START_ODLH");
        actions.add("com.google.android.gms.sesame.service.BIND");
        actions.add("com.google.android.gms.settings.EXPOSURE_NOTIFICATION_SETTINGS");
        actions.add("com.google.android.gms.setup.auth.SecondDeviceAuth.START");
        actions.add("com.google.android.gms.signin.service.START");
        actions.add("com.google.android.gms.smartdevice.d2d.SourceDeviceService.START");
        actions.add("com.google.android.gms.smartdevice.d2d.TargetDeviceService.START");
        actions.add("com.google.android.gms.smartdevice.directtransfer.SourceDirectTransferService.START");
        actions.add("com.google.android.gms.smartdevice.directtransfer.TargetDirectTransferService.START");
        actions.add("com.google.android.gms.smartdevice.postsetup.PostSetupService.START");
        actions.add("com.google.android.gms.smartdevice.setup.accounts.AccountsService.START");
        actions.add("com.google.android.gms.smartdevice.wifi.START_WIFI_HELPER_SERVICE");
        actions.add("com.google.android.gms.social.location.activity.service.START");
        actions.add("com.google.android.gms.speech.service.START");
        actions.add("com.google.android.gms.statementservice.EXECUTE");
        actions.add("com.google.android.gms.stats.ACTION_UPLOAD_DROPBOX_ENTRIES");
        actions.add("com.google.android.gms.tapandpay.service.BIND");
        actions.add("com.google.android.gms.telephonyspam.service.START");
        actions.add("com.google.android.gms.testsupport.service.START");
        actions.add("com.google.android.gms.thunderbird.service.START");
        actions.add("com.google.android.gms.trustagent.BridgeApi.START");
        actions.add("com.google.android.gms.trustagent.StateApi.START");
        actions.add("com.google.android.gms.trustagent.trustlet.trustletmanagerservice.BIND");
        actions.add("com.google.android.gms.trustlet.bluetooth.service.BIND");
        actions.add("com.google.android.gms.trustlet.connectionlessble.service.BIND");
        actions.add("com.google.android.gms.trustlet.face.service.BIND");
        actions.add("com.google.android.gms.trustlet.nfc.service.BIND");
        actions.add("com.google.android.gms.trustlet.onbody.service.BIND");
        actions.add("com.google.android.gms.trustlet.place.service.BIND");
        actions.add("com.google.android.gms.trustlet.voiceunlock.service.BIND");
        actions.add("com.google.android.gms.udc.service.START");
        actions.add("com.google.android.gms.update.START_API_SERVICE");
        actions.add("com.google.android.gms.update.START_SERVICE");
        actions.add("com.google.android.gms.update.START_SINGLE_USER_API_SERVICE");
        actions.add("com.google.android.gms.update.START_TV_API_SERVICE");
        actions.add("com.google.android.gms.usagereporting.service.START");
        actions.add("com.google.android.gms.userlocation.service.START");
        actions.add("com.google.android.gms.vehicle.cabin.service.START");
        actions.add("com.google.android.gms.vehicle.climate.service.START");
        actions.add("com.google.android.gms.vehicle.info.service.START");
        actions.add("com.google.android.gms.wallet.service.BIND");
        actions.add("com.google.android.gms.walletp2p.service.firstparty.BIND");
        actions.add("com.google.android.gms.walletp2p.service.zeroparty.BIND");
        actions.add("com.google.android.gms.wearable.BIND");
        actions.add("com.google.android.gms.wearable.BIND_LISTENER");
        actions.add("com.google.android.gms.wearable.DATA_CHANGED");
        actions.add("com.google.android.gms.wearable.MESSAGE_RECEIVED");
        actions.add("com.google.android.gms.wearable.NODE_CHANGED");
        actions.add("com.google.android.gsf.action.GET_GLS");
        actions.add("com.google.android.location.settings.LOCATION_REPORTING_SETTINGS");
        actions.add("com.google.android.mdd.service.START");
        actions.add("com.google.android.mdh.service.listener.START");
        actions.add("com.google.android.mdh.service.START");
        actions.add("com.google.android.mobstore.service.START");
        actions.add("com.google.firebase.auth.api.gms.service.START");
        actions.add("com.google.firebase.dynamiclinks.service.START");
        actions.add("com.google.iid.TOKEN_REQUEST");
        actions.add("com.google.android.gms.location.places.ui.PICK_PLACE");
        ACTIONS = Collections.unmodifiableSet(actions);

        Set<String> authorities = new HashSet<>();
        authorities.add("com.google.android.gms.auth.accounts");
        authorities.add("com.google.android.gms.chimera");
        authorities.add("com.google.android.gms.fonts");
        authorities.add("com.google.android.gms.phenotype");
        authorities.add("com.google.android.gsf.gservices");
        authorities.add("com.google.settings");
        AUTHORITIES = Collections.unmodifiableSet(authorities);
    }
}
