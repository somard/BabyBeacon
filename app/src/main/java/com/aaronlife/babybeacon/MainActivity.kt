package com.aaronlife.babybeacon

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    class DeviceData {
        var name: String? = null
        var distance: String? = null
        var updateTime: Long = 0
    }

    // 監控更新清單
    private inner class MonitorThread : Thread() {
        override fun run() {
            super.run()
            while (true) {
                for (d in devices) {
                    if (System.currentTimeMillis() - d.updateTime > 5000) {
                        d.distance = "消失"
                        runOnUiThread { deviceListAdapter!!.notifyDataSetChanged() }
                    }
                }

                // 每6秒判斷一次
                try {
                    sleep(6000)
                } catch (e: InterruptedException) {
                }
            }
        }
    }

    private var listItem: ListView? = null
    private var txtMessage: TextView? = null
    private var scrollView: ScrollView? = null
    private val devices = ArrayList<DeviceData>()
    private var deviceListAdapter: DeviceListAdapter? = null
    private var messages = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("aarontest", "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //  ActionBar bar = getSupportActionBar();
        // bar.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#f44336")));

        // 顯示版本名稱
        val txtVersion = findViewById<View>(R.id.version) as TextView
        var pInfo: PackageInfo? = null
        try {
            pInfo = packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        txtVersion.text = pInfo!!.versionName
        txtMessage = findViewById<View>(R.id.message) as TextView
        listItem = findViewById<View>(R.id.list_item) as ListView
        scrollView = findViewById<View>(R.id.scroll) as ScrollView
        deviceListAdapter = DeviceListAdapter(this, devices)
        listItem!!.adapter = deviceListAdapter

        // 注意順序
        bleServiceCheck()

        // 啟動監視
        MonitorThread().start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> {
                devices.clear()
                deviceListAdapter!!.notifyDataSetChanged()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        Log.d("aarontest", "RemoteActivity resume")
        super.onResume()
    }

    override fun onDestroy() {
        Log.d("aarontest", "onDestroy")
        super.onDestroy()

        // 關閉BLE Central掃瞄
        if (BleCentral.getInstance(this) != null) BleCentral.getInstance(this).stop()

        // 關閉BLE Peripheral廣播
        if (BlePeripheral.getInstance(this) != null) BlePeripheral.getInstance(this).stop()
    }

    override fun onBackPressed() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage("確定要結束並離開嗎？\n\n備註：可透過Home鍵移到背景執行")
        builder.setCancelable(true)
        builder.setPositiveButton("是") { dialog, id -> finish() }
        builder.setNegativeButton("不要") { dialog, id -> dialog.cancel() }
        builder.show()
    }

    fun checkDiscoveryPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            // 啟動BLE Central掃瞄
            if (BleCentral.getInstance(this) != null) BleCentral.getInstance(this).start()

            // 啟動BLE Peripheral廣播
            if (BlePeripheral.getInstance(this) != null) BlePeripheral.getInstance(this).start()
        } else {
            // 目前沒有權限，要求權限
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_DISCOVERY_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        when (requestCode) {
            REQUEST_DISCOVERY_PERMISSIONS -> if (grantResults.size >= 1 && permissions[0] == Manifest.permission.ACCESS_COARSE_LOCATION && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 啟動BLE Central掃瞄
                if (BleCentral.getInstance(this) != null) BleCentral.getInstance(this).start()

                // 啟動BLE Peripheral廣播
                if (BlePeripheral.getInstance(this) != null) BlePeripheral.getInstance(this).start()
            } else {
                Toast.makeText(this, "無法取得定位服務權限", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun onSend(v: View) {
        if (BleCentral.getInstance(this) != null) {
            BleCentral.getInstance(this).sendNotify(v.tag as String)
            addMessage("向 " + v.tag as String + " 發出聲音")
        }
    }

    fun playSound(fromDeviceName: String) {
        val mp = MediaPlayer.create(applicationContext, R.raw.bb)
        mp.start()
        addMessage("$fromDeviceName 向你發出聲音")
    }

    fun vibrate(deviceName: String) {
        val vibrator = application.getSystemService(Service.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(500)
        addMessage("已經向 $deviceName 發出聲音")
    }

    @Synchronized
    fun addDevice(device: DeviceData) {
        for (d in devices) {
            // 判斷是不是已經存在了
            if (d.name == device.name) {
                d.distance = device.distance
                d.updateTime = device.updateTime
                deviceListAdapter!!.notifyDataSetChanged()
                return
            }
        }
        devices.add(device)
        deviceListAdapter!!.notifyDataSetChanged()
    }

    fun addMessage(msg: String) {
        runOnUiThread {
            if (messages.length > 0) messages += "\n"

            // 時間字串
            val sdf = SimpleDateFormat("HH:mm", Locale.ENGLISH)
            val datetime = sdf.format(Calendar.getInstance().time)
            messages += "$datetime $msg"
            if (messages.length > 20000) messages.substring(messages.length - 20000)
            txtMessage!!.text = messages

            // 將訊息捲動到最下面
            scrollView!!.post { scrollView!!.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private val LOCATION_REUEST_CODE = 0
    private fun locationServiceCheck() {
        val LOCATION_REUEST_CODE = 0
        Log.d("aarontest", "SDK version: " + Build.VERSION.SDK_INT)

        // Android 6.0以下（不含6.0）不需要Location Service
        if (Build.VERSION.SDK_INT >= 23) {
            val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                    !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val builder = AlertDialog.Builder(this)
                builder.setMessage("定位服務尚未開啟，無法掃描裝置，是否要進行設定？")
                builder.setCancelable(false)
                builder.setPositiveButton("是") { dialog, id ->
                    val it = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivityForResult(it, LOCATION_REUEST_CODE)
                }
                builder.setNegativeButton("不要"
                ) { dialog, id -> dialog.cancel() }
                builder.show()
                return
            }
        }
        checkDiscoveryPermissions() // 明確要求定位服務權限
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LOCATION_REUEST_CODE) {
            checkDiscoveryPermissions() // 明確要求定位服務權限
        }
    }

    private fun bleServiceCheck() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null) {
            while (!adapter.isEnabled) {
                adapter.enable()
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
            locationServiceCheck() // 定位服務是否開啟
        }
    }

    companion object {
        const val REQUEST_DISCOVERY_PERMISSIONS = 0
    }
}