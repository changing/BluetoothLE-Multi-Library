/*
 * Copyright (c) 2016, Qin Dachang
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.github.qindachang.library;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import com.github.qindachang.library.conn.BluetoothUtils;
import com.github.qindachang.library.conn.ConnParameters;
import com.github.qindachang.library.exception.BleException;
import com.github.qindachang.library.exception.ConnBleException;
import com.github.qindachang.library.exception.ReadBleException;
import com.github.qindachang.library.exception.ScanBleException;
import com.github.qindachang.library.exception.WriteBleException;
import com.github.qindachang.library.scanner.BluetoothLeScannerCompat;
import com.github.qindachang.library.scanner.ScanCallback;
import com.github.qindachang.library.scanner.ScanFilter;
import com.github.qindachang.library.scanner.ScanResult;
import com.github.qindachang.library.scanner.ScanSettings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;


/* package */ class BleManager extends BleManagerImpl {

    private static final String TAG = BleManager.class.getSimpleName();

    private static final UUID SERVICE = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS_UUID = UUID.fromString("00002A04-0000-1000-8000-00805f9b34fb");

    private boolean isStopScanAfterConnected;
    private boolean isScanning;
    private boolean mConnected;
    private boolean mServiceDiscovered;
    private boolean mRetryConnectEnable;
    private int mRetryConnectCount = 1;
    private int connectTimeoutMillis;
    private int serviceTimeoutMillis;

    private double connIntervalMin;
    private double connIntervalMax;
    private int slaveLatency;
    private int connSupervisionTimeout;
    private int autoQueueInterval = 400;

    private int queueDelayTime;
    private boolean enableQueueDelay;
    private boolean enableLogger;

    private boolean isReadRssi;

    private boolean mAutoConnect;
    private BluetoothDevice mBluetoothDevice;

    private Context mContext;

    private BluetoothGatt mBluetoothGatt;
    private ConnParameters mConnParameters = new ConnParameters();

    private RequestQueue mRequestQueue = new RequestQueue();
    private Set<LeListener> mListenerList = new LinkedHashSet<>();

    private int readRssiIntervalMillisecond = 1000;
    private Timer mTimer;
    private TimerTask mTimerTask;

    private Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void setConfig(BluetoothConfig config) {
        queueDelayTime = config.getQueueDelayTime();
        enableQueueDelay = config.getEnableQueueDelay();
        enableLogger = config.getEnableLogger();
    }

    @Override
    public boolean isSupportBluetooth() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return bluetoothAdapter != null;
    }

    @Override
    public boolean isBluetoothOpen() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return bluetoothAdapter.isEnabled();
    }

    @Override
    public boolean enableBluetooth(Activity activity) {
        synchronized (BleManager.class) {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                BleLogger.e(enableLogger, TAG, "false. your device does not support bluetooth. ");
                return false;
            }
            if (bluetoothAdapter.isEnabled()) {
                BleLogger.d(enableLogger, TAG, "false. your device has been turn on bluetooth.");
                return false;
            }
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivity(intent);
            return true;
        }
    }

    @Override
    public boolean enableBluetooth(Activity activity, int requestCode) {
        synchronized (BleManager.class) {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                BleLogger.e(enableLogger, TAG, "false. your device does not support bluetooth. ");
                return false;
            }
            if (bluetoothAdapter.isEnabled()) {
                BleLogger.d(enableLogger, TAG, "false. your device has been turn on bluetooth.");
                return false;
            }
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(intent, requestCode);
            return true;
        }
    }

    @Override
    public boolean disableBluetooth() {
        synchronized (BleManager.class) {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter.isEnabled()) {
                bluetoothAdapter.disable();
                return true;
            } else {
                BleLogger.d(enableLogger, TAG, "false. your device has been turn off Bluetooth.");
                return false;
            }
        }
    }

    private static boolean isAndroidMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private void runOnUiThread(Runnable runnable) {
        if (isAndroidMainThread()) {
            runnable.run();
        } else {
            mHandler.post(runnable);
        }
    }

    @Override
    public boolean clearDeviceCache() {
        synchronized (BleManager.class) {
            if (mBluetoothGatt == null) {
                BleLogger.e(enableLogger, TAG, "please connected bluetooth then clear cache.");
                return false;
            }
            try {
                Method e = BluetoothGatt.class.getMethod("refresh", new Class[0]);
                if (e != null) {
                    boolean success = ((Boolean) e.invoke(mBluetoothGatt, new Object[0])).booleanValue();
                    BleLogger.i(enableLogger, TAG, "refresh Device Cache: " + success);
                    return success;
                }
            } catch (Exception exception) {
                BleLogger.e(enableLogger, TAG, "An exception occured while refreshing device", exception);
            }
            return false;
        }
    }

    public boolean addLeListenerList(LeListener leListener) {
        return mListenerList.add(leListener);
    }

    private List<String> filterDeviceNameList = new ArrayList<>();
    private List<String> filterDeviceAddressList = new ArrayList<>();
    private List<UUID> filerServiceUUIDList = new ArrayList<>();
    private int scanPeriod = 10000;
    private int reportDelayMillis;

    @Override
    public BleManager setScanWithDeviceName(String deviceName) {
        this.filterDeviceNameList.add(deviceName);
        return this;
    }

    @Override
    public BleManager setScanWithDeviceName(String[] deviceNames) {
        Collections.addAll(this.filterDeviceNameList, deviceNames);
        return this;
    }

    @Override
    public BleManager setScanWithDeviceAddress(String deviceAddress) {
        this.filterDeviceAddressList.add(deviceAddress);
        return this;
    }

    @Override
    public BleManager setScanWithDeviceAddress(String[] deviceAddress) {
        Collections.addAll(this.filterDeviceAddressList, deviceAddress);
        return this;
    }

    @Override
    public BleManager setScanWithServiceUUID(String serviceUUID) {
        setScanWithServiceUUID(UUID.fromString(serviceUUID));
        return this;
    }

    @Override
    public BleManager setScanWithServiceUUID(String[] serviceUUIDs) {
        for (String serviceUUID : serviceUUIDs) {
            setScanWithServiceUUID(UUID.fromString(serviceUUID));
        }
        return this;
    }

    @Override
    public BleManager setScanWithServiceUUID(UUID serviceUUID) {
        this.filerServiceUUIDList.add(serviceUUID);
        return this;
    }

    @Override
    public BleManager setScanWithServiceUUID(UUID[] serviceUUIDs) {
        Collections.addAll(this.filerServiceUUIDList, serviceUUIDs);
        return this;
    }

    @Override
    public BleManager setScanPeriod(int millisecond) {
        this.scanPeriod = millisecond;
        return this;
    }

    @Override
    public BleManager setReportDelay(int reportDelayMillis) {
        this.reportDelayMillis = reportDelayMillis;
        return this;
    }

    @Override
    public void scan() {
        scan(filterDeviceNameList, filterDeviceAddressList, filerServiceUUIDList, scanPeriod, reportDelayMillis);
    }

    private void scan(List<String> filterDeviceNameList, List<String> filterDeviceAddressList, List<UUID> filerServiceUUIDList,
                      int scanPeriod, int reportDelayMillis) {
        BleLogger.d(enableLogger, TAG, "bluetooth le scanning...");

        stopScan();

        BluetoothLeScannerCompat scannerCompat = BluetoothLeScannerCompat.getScanner();
        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(reportDelayMillis)
                .setUseHardwareBatchingIfSupported(false)
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        for (String deviceName : filterDeviceNameList) {
            ScanFilter builder = new ScanFilter.Builder().setDeviceName(deviceName).build();
            filters.add(builder);
        }
        for (String deviceAddress : filterDeviceAddressList) {
            ScanFilter builder = new ScanFilter.Builder().setDeviceAddress(deviceAddress).build();
            filters.add(builder);
        }
        for (UUID serviceUUID : filerServiceUUIDList) {
            ScanFilter builder = new ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(serviceUUID.toString())).build();
            filters.add(builder);
        }

        scannerCompat.startScan(filters, scanSettings, scanCallback);

        int SCAN_DURATION = scanPeriod;
        if (SCAN_DURATION == 0) {
            SCAN_DURATION = 10000;
        }
        isScanning = true;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isScanning) {
                    stopScan();
                }
            }
        }, SCAN_DURATION);
    }

    @Override
    public void stopScan() {
        if (isScanning) {
            final BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
            scanner.stopScan(scanCallback);
            isScanning = false;
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeScanListener) {
                    ((OnLeScanListener) leListener).onScanCompleted();
                }
            }
            BleLogger.d(enableLogger, TAG, "bluetooth le scan has stop.");
        }
    }

    @Override
    public boolean getScanning() {
        return isScanning;
    }

    @Override
    public BleManager setStopScanAfterConnected(boolean stop) {
        isStopScanAfterConnected = stop;
        return this;
    }

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(final int callbackType, final ScanResult result) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeScanListener) {
                    ((OnLeScanListener) leListener).onScanResult(result.getDevice(), result.getRssi(), result.getScanRecord());
                }
            }
        }

        @Override
        public void onBatchScanResults(final List<ScanResult> results) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeScanListener) {
                    ((OnLeScanListener) leListener).onBatchScanResults(results);
                }
            }
        }

        @Override
        public void onScanFailed(final int errorCode) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeScanListener) {
                    ((OnLeScanListener) leListener).onScanFailed(
                            new ScanBleException(errorCode, BleException.SCAN));
                }
            }
        }
    };

    @Override
    public BleManager setRetryConnectEnable(boolean retryConnectEnable) {
        mRetryConnectEnable = retryConnectEnable;
        return this;
    }

    @Override
    public BleManager setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    @Override
    public BleManager setServiceTimeoutMillis(int serviceTimeoutMillis) {
        this.serviceTimeoutMillis = serviceTimeoutMillis;
        return this;
    }

    @Override
    public BleManager setRetryConnectCount(int retryConnectCount) {
        mRetryConnectCount = retryConnectCount;
        return this;
    }

    @Override
    public boolean connect(boolean autoConnect, final BluetoothDevice device) {
        mAutoConnect = autoConnect;
        mBluetoothDevice = device;
        if (mBluetoothDevice == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeConnectListener) {
                    ((OnLeConnectListener) leListener).onDeviceConnectFail(
                            new ConnBleException(233, BleException.CONNECT,
                                    "bluetoothDevice.connectGatt(..) on a null object reference. check bluetoothDevice object is not null.")
                    );
                }
            }
            return false;
        }
        if (mConnected) {
            BleLogger.d(enableLogger, TAG, "Bluetooth has been connected. connect false.");
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeConnectListener) {
                    ((OnLeConnectListener) leListener).onDeviceConnectFail(
                            new ConnBleException(0, BleException.CONNECT, "Bluetooth has been connected. connect false."));
                }
            }
            return false;
        }
        if (mBluetoothGatt != null) {
            BleLogger.d(enableLogger, TAG, "The BluetoothGatt already exist, set it close() and null.");
            mBluetoothGatt.close();
            mBluetoothGatt = null;
            mConnected = false;
        }
        BleLogger.d(enableLogger, TAG, "create new device connection for BluetoothGatt. ");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mBluetoothGatt = device.connectGatt(mContext, autoConnect, mGattCallback, TRANSPORT_LE);
        } else {
            mBluetoothGatt = device.connectGatt(mContext, autoConnect, mGattCallback);
        }
        for (LeListener leListener : mListenerList) {
            if (leListener instanceof OnLeConnectListener) {
                ((OnLeConnectListener) leListener).onDeviceConnecting();
            }
        }

        checkConnected();

        return true;
    }

    @Override
    public BluetoothDevice getBluetoothDevice() {
        if (mConnected) {
            return mBluetoothDevice;
        } else
            return null;
    }

    BluetoothGatt getBluetoothGatt() {
        return mBluetoothGatt;
    }

    private void checkConnected() {
        if (mRetryConnectEnable && mRetryConnectCount > 0 && connectTimeoutMillis > 0) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    boolean connected = getConnected();
                    if (!connected) {
                        connect(mAutoConnect, mBluetoothDevice);
                        mRetryConnectCount = mRetryConnectCount - 1;
                    }
                }
            }, connectTimeoutMillis);
        }
    }

    @Override
    public boolean getConnected() {
        return mConnected;
    }

    @Override
    public boolean getServicesDiscovered() {
        return mServiceDiscovered;
    }

    private boolean enableNotification(boolean enable, BluetoothGattCharacteristic characteristic) {
        final BluetoothGatt gatt = mBluetoothGatt;
        if (gatt == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeNotificationListener) {
                    ((OnLeNotificationListener) leListener).onFailed(
                            new BleException(233, BleException.NOTIFICATION,
                                    "BluetoothGatt object is null. check connect status and onServicesDiscovered."));
                }
            }
            mRequestQueue.next();
            return false;
        }
        if (characteristic == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeNotificationListener) {
                    ((OnLeNotificationListener) leListener).onFailed(
                            new BleException(233, BleException.NOTIFICATION,
                                    "characteristic uuid is null."));
                }
            }
            mRequestQueue.next();
            return false;
        }
        final int properties = characteristic.getProperties();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            BleLogger.d(enableLogger, TAG, "uuid:" + characteristic.getUuid() + ", does not support notification");
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeNotificationListener) {
                    ((OnLeNotificationListener) leListener).onFailed(
                            new BleException(233, BleException.NOTIFICATION,
                                    "characteristic uuid : " + characteristic.getUuid() + ", does not support notification"));
                }
            }
            mRequestQueue.next();
            return false;
        }
        gatt.setCharacteristicNotification(characteristic, enable);
        BleLogger.d(enableLogger, TAG, "setCharacteristicNotification uuid:" + characteristic.getUuid() + " ," + enable);
        final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            BleLogger.d(enableLogger, TAG, "writeDescriptor(notification), " + CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
            return gatt.writeDescriptor(descriptor);
        } else {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeNotificationListener) {
                    ((OnLeNotificationListener) leListener).onFailed(
                            new BleException(233, BleException.NOTIFICATION,
                                    "characteristic uuid : " + characteristic.getUuid() + ", does not contain descriptor."));
                }
            }
            mRequestQueue.next();
            return false;
        }
    }

    @Override
    public void enableNotifications(boolean enable, UUID serviceUUID, UUID[] characteristicUUIDs) {
        if (mBluetoothGatt == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeNotificationListener) {
                    ((OnLeNotificationListener) leListener).onFailed(
                            new BleException(233, BleException.NOTIFICATION,
                                    "BluetoothGatt object is null. check connect status and onServicesDiscovered."));
                }
            }
            return;
        }
        BluetoothGattService service = mBluetoothGatt.getService(serviceUUID);
        if (service != null) {
            for (UUID characteristicUUID : characteristicUUIDs) {
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
                if (characteristic == null) {
                    for (LeListener leListener : mListenerList) {
                        if (leListener instanceof OnLeNotificationListener) {
                            ((OnLeNotificationListener) leListener).onFailed(
                                    new BleException(233, BleException.NOTIFICATION,
                                            "can not find characteristic form given characteristic uuid : " + characteristicUUID +
                                                    ", where in given service uuid : " + serviceUUID));
                        }
                    }
                } else {
                    mRequestQueue.addRequest(Request.newEnableNotificationsRequest(enable, characteristic));
                }
            }
        } else {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeNotificationListener) {
                    ((OnLeNotificationListener) leListener).onFailed(
                            new BleException(233, BleException.NOTIFICATION,
                                    "can not find service form given service uuid : " + serviceUUID.toString()));
                }
            }
        }
    }

    private boolean enableIndication(boolean enable, BluetoothGattCharacteristic characteristic) {
        final BluetoothGatt gatt = mBluetoothGatt;
        if (gatt == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeIndicationListener) {
                    ((OnLeIndicationListener) leListener).onFailed(
                            new BleException(233, BleException.INDICATION,
                                    "BluetoothGatt object is null. check connect status and onServicesDiscovered."));
                }
            }
            mRequestQueue.next();
            return false;
        }
        if (characteristic == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeIndicationListener) {
                    ((OnLeIndicationListener) leListener).onFailed(
                            new BleException(233, BleException.INDICATION,
                                    "characteristic uuid is null."));
                }
            }
            mRequestQueue.next();
            return false;
        }
        final int properties = characteristic.getProperties();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
            BleLogger.e(enableLogger, TAG, "uuid:" + characteristic.getUuid() + ", does not support indication");
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeIndicationListener) {
                    ((OnLeIndicationListener) leListener).onFailed(
                            new BleException(233, BleException.INDICATION,
                                    "characteristic uuid : " + characteristic.getUuid() + ", does not support indication."));
                }
            }
            mRequestQueue.next();
            return false;
        }
        gatt.setCharacteristicNotification(characteristic, enable);
        BleLogger.d(enableLogger, TAG, "setCharacteristicNotification uuid:" + characteristic.getUuid() + " ," + enable);
        final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            BleLogger.d(enableLogger, TAG, "writeDescriptor(indication), " + CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
            return gatt.writeDescriptor(descriptor);
        } else {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeIndicationListener) {
                    ((OnLeIndicationListener) leListener).onFailed(
                            new BleException(233, BleException.INDICATION,
                                    "characteristic uuid : " + characteristic.getUuid() + ", does not contain descriptor."));
                }
            }
            mRequestQueue.next();
            return false;
        }
    }

    @Override
    public void enableIndicates(boolean enable, UUID serviceUUID, UUID[] characteristicUUIDs) {
        if (mBluetoothGatt == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeIndicationListener) {
                    ((OnLeIndicationListener) leListener).onFailed(
                            new BleException(233, BleException.INDICATION,
                                    "BluetoothGatt object is null. check connect status and onServicesDiscovered.")
                    );
                }
            }
            return;
        }
        BluetoothGattService service = mBluetoothGatt.getService(serviceUUID);
        if (service != null) {
            for (UUID characteristicUUID : characteristicUUIDs) {
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
                if (characteristic == null) {
                    for (LeListener leListener : mListenerList) {
                        if (leListener instanceof OnLeIndicationListener) {
                            ((OnLeIndicationListener) leListener).onFailed(
                                    new BleException(233, BleException.INDICATION,
                                            "can not find characteristic form given characteristic uuid : " + characteristicUUID +
                                                    ", where in given service uuid : " + serviceUUID));
                        }
                    }
                } else {
                    mRequestQueue.addRequest(Request.newEnableIndicationsRequest(enable, service.getCharacteristic(characteristicUUID)));
                }
            }
        } else {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeIndicationListener) {
                    ((OnLeIndicationListener) leListener).onFailed(
                            new BleException(233, BleException.INDICATION,
                                    "can not find service form given service uuid : " + serviceUUID.toString()
                            )
                    );
                }
            }
        }
    }

    @Override
    public void writeBytesToCharacteristic(byte[] bytes, UUID serviceUUID, UUID characteristicUUID) {
        if (mBluetoothGatt == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeWriteCharacteristicListener) {
                    ((OnLeWriteCharacteristicListener) leListener).onFailed(
                            new WriteBleException(233, BleException.WRITE_CHARACTERISTIC,
                                    "bluetoothGatt is null. check connect status and onServicesDiscovered.")
                    );
                }
            }
            return;
        }
        if (serviceUUID == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeWriteCharacteristicListener) {
                    ((OnLeWriteCharacteristicListener) leListener).onFailed(
                            new WriteBleException(233, BleException.WRITE_CHARACTERISTIC, "service uuid is null")
                    );
                }
            }
            return;
        }
        if (characteristicUUID == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeWriteCharacteristicListener) {
                    ((OnLeWriteCharacteristicListener) leListener).onFailed(
                            new WriteBleException(233, BleException.WRITE_CHARACTERISTIC, "characteristic uuid is null")
                    );
                }
            }
            return;
        }
        BluetoothGattService service = mBluetoothGatt.getService(serviceUUID);
        if (service != null) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
            if (characteristic == null) {
                for (LeListener leListener : mListenerList) {
                    if (leListener instanceof OnLeWriteCharacteristicListener) {
                        ((OnLeWriteCharacteristicListener) leListener).onFailed(
                                new WriteBleException(233, BleException.WRITE_CHARACTERISTIC,
                                        "can not find characteristic form given characteristic uuid : " + characteristicUUID +
                                                ", where in given service uuid : " + serviceUUID));
                    }
                }
            } else {
                characteristic.setValue(bytes);
                mRequestQueue.addRequest(Request.newWriteRequest(characteristic));
            }
        } else {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeWriteCharacteristicListener) {
                    ((OnLeWriteCharacteristicListener) leListener).onFailed(
                            new WriteBleException(233, BleException.WRITE_CHARACTERISTIC,
                                    "can not find service from given service uuid : " + serviceUUID.toString())
                    );
                }
            }
        }
    }

    @Override
    public BluetoothGattCharacteristic getCharacteristic(UUID serviceUUID, UUID characteristicUUID) {
        BluetoothGattService service = mBluetoothGatt.getService(serviceUUID);
        if (service == null) {
            return null;
        }
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
        if (characteristic == null) {
            return null;
        }
        return characteristic;
    }

    @Override
    public void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeWriteCharacteristicListener) {
                    ((OnLeWriteCharacteristicListener) leListener).onFailed(
                            new WriteBleException(233, BleException.WRITE_CHARACTERISTIC, "characteristic is null"));
                }
            }
            return;
        }
        mRequestQueue.addRequest(Request.newWriteRequest(characteristic));
    }


    private boolean write2Characteristic(BluetoothGattCharacteristic characteristic) {
        final BluetoothGatt gatt = mBluetoothGatt;
        if (gatt == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeWriteCharacteristicListener) {
                    ((OnLeWriteCharacteristicListener) leListener).onFailed(
                            new WriteBleException(233, BleException.WRITE_CHARACTERISTIC,
                                    "BluetoothGatt object is null. check connect status and onServicesDiscovered.")
                    );
                }
            }
            mRequestQueue.next();
            return false;
        }
        if (characteristic == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeWriteCharacteristicListener) {
                    ((OnLeWriteCharacteristicListener) leListener).onFailed(
                            new WriteBleException(233, BleException.WRITE_CHARACTERISTIC, "characteristic uuid is null.")
                    );
                }
            }
            mRequestQueue.next();
            return false;
        }
        // Check characteristic property
        final int properties = characteristic.getProperties();
        if ((properties & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeWriteCharacteristicListener) {
                    ((OnLeWriteCharacteristicListener) leListener).onFailed(
                            new WriteBleException(233, BleException.WRITE_CHARACTERISTIC,
                                    "characteristic : " + characteristic.getUuid() + ", property not support write.")
                    );
                }
            }
            mRequestQueue.next();
            return false;
        }

        return gatt.writeCharacteristic(characteristic);
    }

    @Override
    public void readCharacteristic(UUID serviceUUID, UUID characteristicUUID) {
        if (mBluetoothGatt == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeReadCharacteristicListener) {
                    ((OnLeReadCharacteristicListener) leListener).onFailure(
                            new ReadBleException(233, BleException.READ_CHARACTERISTIC,
                                    "BluetoothGatt object is null. check connect status and onServicesDiscovered.")
                    );
                }
            }
            return;
        }
        BluetoothGattService service = mBluetoothGatt.getService(serviceUUID);
        if (service != null) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
            if (characteristic == null) {
                for (LeListener leListener : mListenerList) {
                    if (leListener instanceof OnLeReadCharacteristicListener) {
                        ((OnLeReadCharacteristicListener) leListener).onFailure(
                                new ReadBleException(233, BleException.READ_CHARACTERISTIC,
                                        "can not find characteristic form given characteristic uuid : " + characteristicUUID +
                                                ", where in given service uuid : " + serviceUUID));
                    }
                }
            } else {
                mRequestQueue.addRequest(Request.newReadRequest(characteristic));
            }
        } else {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeReadCharacteristicListener) {
                    ((OnLeReadCharacteristicListener) leListener).onFailure(
                            new ReadBleException(233, BleException.READ_CHARACTERISTIC,
                                    "can not find service form given service uuid : " + serviceUUID)
                    );
                }
            }
        }
    }

    private boolean read2Characteristic(BluetoothGattCharacteristic characteristic) {
        final BluetoothGatt gatt = mBluetoothGatt;
        if (gatt == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeReadCharacteristicListener) {
                    ((OnLeReadCharacteristicListener) leListener).onFailure(
                            new ReadBleException(233, BleException.READ_CHARACTERISTIC,
                                    "BluetoothGatt object is null. check connect status and onServicesDiscovered.")
                    );
                }
            }
            mRequestQueue.next();
            return false;
        }
        if (characteristic == null) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeReadCharacteristicListener) {
                    ((OnLeReadCharacteristicListener) leListener).onFailure(
                            new ReadBleException(233, BleException.READ_CHARACTERISTIC,
                                    "characteristic uuid is null.")
                    );
                }
            }
            mRequestQueue.next();
            return false;
        }
        // Check characteristic property
        final int properties = characteristic.getProperties();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            for (LeListener leListener : mListenerList) {
                if (leListener instanceof OnLeReadCharacteristicListener) {
                    ((OnLeReadCharacteristicListener) leListener).onFailure(
                            new ReadBleException(233, BleException.READ_CHARACTERISTIC,
                                    "characteristic : " + characteristic.toString() + ", property not support read.")
                    );
                }
            }
            mRequestQueue.next();
            return false;
        }
        return gatt.readCharacteristic(characteristic);
    }

    void readRssi() {
        if (mConnected) {
            readRssiTimerTask();
        } else {
            isReadRssi = true;
        }
    }

    void setReadRssiIntervalMillisecond(int millisecond) {
        readRssiIntervalMillisecond = millisecond;
    }

    void cancelReadRssiTimerTask() {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
        if (mTimerTask != null) {
            mTimerTask.cancel();
            mTimerTask = null;
        }
    }

    private void readRssiTimerTask() {
        mTimer = null;
        mTimerTask = null;
        mTimer = new Timer();
        mTimerTask = new TimerTask() {
            @Override
            public void run() {
                if (mBluetoothGatt != null) {
                    mBluetoothGatt.readRemoteRssi();
                }
            }
        };
        mTimer.schedule(mTimerTask, 100, readRssiIntervalMillisecond);
    }

    void disconnect() {
        if (mConnected && mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
            mConnected = false;
            mServiceDiscovered = false;
            cancelReadRssiTimerTask();
            mBluetoothGatt = null;
        }
    }

    void close() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
            mConnected = false;
            mServiceDiscovered = false;
        }
    }

    private void checkServiceDiscover() {
        if (mRetryConnectEnable && mRetryConnectCount > 0 && serviceTimeoutMillis > 0) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!mServiceDiscovered) {
                        connect(mAutoConnect, mBluetoothDevice);
                        mRetryConnectCount -= 1;
                    }
                }
            }, serviceTimeoutMillis);
        }
    }

    private void readConnectionParameters() {
        if (mBluetoothGatt == null) {
            return;
        }
        readCharacteristic(SERVICE, PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS_UUID);
    }

    ConnParameters getConnParameters() {
        return mConnParameters;
    }

    private BleManagerGattCallback mGattCallback = new BleManagerGattCallback() {

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                BleLogger.d(enableLogger, TAG, "device connect success!");
                mConnected = true;
                if (isStopScanAfterConnected) {
                    stopScan();
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeConnectListener) {
                                ((OnLeConnectListener) leListener).onDeviceConnected();
                            }
                        }
                    }
                });

                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (gatt.getDevice().getBondState() != BluetoothDevice.BOND_BONDING) {
                            if (mBluetoothGatt != null) {
                                mBluetoothGatt.discoverServices();
                                checkServiceDiscover();
                            }
                        }
                    }
                }, 600);

                if (isReadRssi) {
                    cancelReadRssiTimerTask();
                    readRssiTimerTask();
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                BleLogger.d(enableLogger, TAG, "device disconnect.");
                mConnected = false;
                mServiceDiscovered = false;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeConnectListener) {
                                ((OnLeConnectListener) leListener).onDeviceDisconnected();
                            }
                        }
                    }
                });
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeConnectListener) {
                                ((OnLeConnectListener) leListener).onDeviceConnectFail(
                                        new ConnBleException(status, BleException.CONNECT, "Error on connection state change.")
                                );
                            }
                        }
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                BleLogger.d(enableLogger, TAG, "success with find services discovered .");
                mServiceDiscovered = true;

                readConnectionParameters();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeConnectListener) {
                                ((OnLeConnectListener) leListener).onServicesDiscovered(gatt);
                            }
                        }
                    }
                });

            } else {
                BleLogger.d(enableLogger, TAG, "failure find services discovered.");
                mServiceDiscovered = false;
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            //read
            if (status == BluetoothGatt.GATT_SUCCESS) {

                if (characteristic.getUuid().equals(PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS_UUID)) {
                    List<Integer> parameters = BluetoothUtils.bytes2IntegerList(characteristic.getValue());
                    connIntervalMin = (parameters.get(1) * 16 + parameters.get(0)) * 1.25;
                    connIntervalMax = (parameters.get(3) * 16 + parameters.get(2)) * 1.25;
                    slaveLatency = parameters.get(5) * 16 + parameters.get(4);
                    connSupervisionTimeout = parameters.get(7) * 16 + parameters.get(6);
                    mConnParameters.setUUID(PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS_UUID);
                    mConnParameters.setConnIntervalMin(connIntervalMin);
                    mConnParameters.setConnIntervalMax(connIntervalMax);
                    mConnParameters.setProperties("READ");
                    mConnParameters.setSlaveLatency(slaveLatency);
                    mConnParameters.setSupervisionTimeout(connSupervisionTimeout);
                    autoQueueInterval = (int) connIntervalMax + 50;
                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            for (LeListener leListener : mListenerList) {
                                if (leListener instanceof OnLeReadCharacteristicListener) {
                                    ((OnLeReadCharacteristicListener) leListener).onSuccess(characteristic);
                                }
                            }
                        }
                    });
                }

            } else if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeReadCharacteristicListener) {
                                ((OnLeReadCharacteristicListener) leListener).onFailure(
                                        new ReadBleException(status, BleException.READ_CHARACTERISTIC, "Phone has lost bonding information."));
                            }
                        }
                    }
                });

            } else {

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeReadCharacteristicListener) {
                                ((OnLeReadCharacteristicListener) leListener).onFailure(
                                        new ReadBleException(status, BleException.READ_CHARACTERISTIC, "Error on reading characteristic."));
                            }
                        }
                    }
                });

            }

            mRequestQueue.next();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeWriteCharacteristicListener) {
                                ((OnLeWriteCharacteristicListener) leListener).onSuccess(characteristic);
                            }
                        }
                    }
                });

            } else if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeWriteCharacteristicListener) {
                                ((OnLeWriteCharacteristicListener) leListener).onFailed(
                                        new WriteBleException(status, BleException.WRITE_CHARACTERISTIC, "Phone has lost of bonding information."));
                            }
                        }
                    }
                });

            } else {

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeWriteCharacteristicListener) {
                                ((OnLeWriteCharacteristicListener) leListener).onFailed(
                                        new WriteBleException(status, BleException.WRITE_CHARACTERISTIC, "Error on reading characteristic."));
                            }
                        }
                    }
                });

            }
            mRequestQueue.next();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    final BluetoothGattDescriptor cccd = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
                    final boolean notifications = cccd == null || cccd.getValue() == null || cccd.getValue().length != 2 || cccd.getValue()[0] == 0x01;

                    if (notifications) {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeNotificationListener) {
                                ((OnLeNotificationListener) leListener).onSuccess(characteristic);
                            }
                        }
                    } else {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeIndicationListener) {
                                ((OnLeIndicationListener) leListener).onSuccess(characteristic);
                            }
                        }
                    }
                }
            });

        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            mRequestQueue.next();
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, final int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (LeListener leListener : mListenerList) {
                            if (leListener instanceof OnLeReadRssiListener) {
                                ((OnLeReadRssiListener) leListener).onSuccess(rssi, BluetoothUtils.getDistance(rssi));
                            }
                        }
                    }
                });
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
        }
    };

    @Override
    public void destroy(Object tag) {
        cancelTag(tag);
    }

    private void cancelTag(Object tag) {
        synchronized (BleManager.class) {
            List<LeListener> leListenerList = new ArrayList<>();
            for (LeListener leListener : mListenerList) {
                if (leListener.getTag() == tag) {
                    leListenerList.add(leListener);
                }
            }
            cancelTagList(leListenerList);
        }
    }

    private void cancelTagList(List<LeListener> list) {
        if (list.size() > 0 && !list.isEmpty()) {
            mListenerList.remove(list.get(0));
            if (list.size() > 0 && !list.isEmpty()) {
                list.remove(0);
                cancelTagList(list);
            }
        }
    }

    @Override
    public void cancelAllTag() {
        synchronized (BleManager.class) {
            mListenerList.clear();
        }
    }

    @Override
    public void clearQueue() {
        synchronized (BleManager.class) {
            mRequestQueue.cancelAll();
        }
    }

    private class RequestQueue {

        private Queue<Request> mRequestQueue = new LinkedList<>();

        void addRequest(Request request) {
            int oldSize = mRequestQueue.size();
            mRequestQueue.add(request);
            if (mRequestQueue.size() == 1 && oldSize == 0) {
                startExecutor();
            }
        }

        private void startExecutor() {
            Request request = mRequestQueue.peek();
            switch (request.type) {
                case WRITE:
                    write2Characteristic(request.getCharacteristic());
                    break;
                case READ:
                    read2Characteristic(request.getCharacteristic());
                    break;
                case ENABLE_NOTIFICATIONS:
                    enableNotification(request.isEnable(), request.getCharacteristic());
                    break;
                case ENABLE_INDICATIONS:
                    enableIndication(request.isEnable(), request.getCharacteristic());
                    break;
            }
        }

        void next() {
            if (enableQueueDelay) {
                if (queueDelayTime < 0) {
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runQueue();
                        }
                    }, autoQueueInterval);
                } else {
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runQueue();
                        }
                    }, queueDelayTime);
                }
            } else {
                runQueue();
            }
        }

        void runQueue() {
            mRequestQueue.poll();
            if (mRequestQueue != null && mRequestQueue.size() > 0) {
                startExecutor();
            }
        }

        void cancelAll() {
            mRequestQueue.clear();
        }

    }

}
