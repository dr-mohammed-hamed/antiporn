package com.drmohammed.antiporn

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverallStatusBadge: TextView
    private lateinit var tvOverallStatusDesc: TextView
    private lateinit var layoutMainStatus: LinearLayout
    private lateinit var cardMainStatus: MaterialCardView

    private lateinit var tvDeviceOwnerStatus: TextView
    private lateinit var tvDnsHostStatus: TextView
    private lateinit var tvDnsLockSubtext: TextView
    private lateinit var tvVpnStatus: TextView
    private lateinit var cardAdbHelp: MaterialCardView
    private lateinit var btnEnforceNow: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        val currentStatus = DnsManager.checkStatus(this)
        if (currentStatus.isDeviceOwner) {
            DnsWatchdogService.startService(this)
            if (!currentStatus.isDnsConfigured || !currentStatus.isVpnBlocked) {
                btnEnforceNow.isEnabled = false
                btnEnforceNow.setText(R.string.btn_enforcing)
                DnsManager.applyPoliciesAsync(this) { result ->
                    if (!isFinishing && !isDestroyed) {
                        btnEnforceNow.isEnabled = true
                        btnEnforceNow.setText(R.string.btn_enforce_now)
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        refreshUi()
                    }
                }
            }
        }
        refreshUi()
    }

    private fun initViews() {
        tvOverallStatusBadge = findViewById(R.id.tvOverallStatusBadge)
        tvOverallStatusDesc = findViewById(R.id.tvOverallStatusDesc)
        layoutMainStatus = findViewById(R.id.layoutMainStatus)
        cardMainStatus = findViewById(R.id.cardMainStatus)

        tvDeviceOwnerStatus = findViewById(R.id.tvDeviceOwnerStatus)
        tvDnsHostStatus = findViewById(R.id.tvDnsHostStatus)
        tvDnsLockSubtext = findViewById(R.id.tvDnsLockSubtext)
        tvVpnStatus = findViewById(R.id.tvVpnStatus)
        cardAdbHelp = findViewById(R.id.cardAdbHelp)
        btnEnforceNow = findViewById(R.id.btnEnforceNow)
    }

    private fun setupListeners() {
        btnEnforceNow.setOnClickListener {
            btnEnforceNow.isEnabled = false
            btnEnforceNow.setText(R.string.btn_enforcing)
            DnsManager.applyPoliciesAsync(this) { result ->
                if (!isFinishing && !isDestroyed) {
                    btnEnforceNow.isEnabled = true
                    btnEnforceNow.setText(R.string.btn_enforce_now)
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                    refreshUi()
                }
            }
        }
    }

    private fun refreshUi() {
        val status = DnsManager.checkStatus(this)

        val greenColor = ContextCompat.getColor(this, R.color.status_green)
        val greenBgColor = ContextCompat.getColor(this, R.color.status_green_bg)
        val orangeColor = ContextCompat.getColor(this, R.color.status_orange)
        val orangeBgColor = ContextCompat.getColor(this, R.color.status_orange_bg)

        if (status.isDeviceOwner) {
            cardAdbHelp.visibility = View.GONE
            tvDeviceOwnerStatus.text = getString(R.string.card_device_owner_active)
            tvDeviceOwnerStatus.setTextColor(greenColor)

            if (status.isDnsConfigured && status.isVpnBlocked) {
                tvOverallStatusBadge.text = getString(R.string.status_active_title)
                tvOverallStatusBadge.setTextColor(greenColor)
                tvOverallStatusDesc.text = getString(R.string.status_active_desc)
                layoutMainStatus.setBackgroundColor(greenBgColor)
                cardMainStatus.strokeColor = greenColor
            } else {
                tvOverallStatusBadge.text = getString(R.string.status_need_enforce_title)
                tvOverallStatusBadge.setTextColor(orangeColor)
                tvOverallStatusDesc.text = getString(R.string.status_need_enforce_desc)
                layoutMainStatus.setBackgroundColor(orangeBgColor)
                cardMainStatus.strokeColor = orangeColor
            }

            if (status.isDnsConfigured) {
                tvDnsHostStatus.text = getString(R.string.card_nextdns_active, DnsManager.NEXTDNS_HOST)
                tvDnsHostStatus.setTextColor(greenColor)
                tvDnsLockSubtext.text = if (status.isPrivateDnsLocked) {
                    getString(R.string.card_nextdns_locked)
                } else {
                    getString(R.string.card_nextdns_unlocked)
                }
            } else {
                val currentHostDisplay = status.currentDnsHost ?: getString(R.string.default_dns_text)
                tvDnsHostStatus.text = getString(R.string.card_nextdns_pending, currentHostDisplay, DnsManager.NEXTDNS_HOST)
                tvDnsHostStatus.setTextColor(orangeColor)
            }

            if (status.isVpnBlocked) {
                tvVpnStatus.text = getString(R.string.card_vpn_blocked)
                tvVpnStatus.setTextColor(greenColor)
            } else {
                tvVpnStatus.text = getString(R.string.card_vpn_unblocked)
                tvVpnStatus.setTextColor(orangeColor)
            }

        } else {
            // Not Device Owner
            cardAdbHelp.visibility = View.VISIBLE
            tvOverallStatusBadge.text = getString(R.string.status_no_device_owner_title)
            tvOverallStatusBadge.setTextColor(orangeColor)
            tvOverallStatusDesc.text = getString(R.string.status_no_device_owner_desc)
            layoutMainStatus.setBackgroundColor(orangeBgColor)
            cardMainStatus.strokeColor = orangeColor

            tvDeviceOwnerStatus.text = getString(R.string.card_device_owner_inactive)
            tvDeviceOwnerStatus.setTextColor(orangeColor)

            tvDnsHostStatus.text = getString(R.string.card_nextdns_inactive)
            tvDnsHostStatus.setTextColor(orangeColor)

            tvVpnStatus.text = getString(R.string.card_vpn_inactive)
            tvVpnStatus.setTextColor(orangeColor)
        }
    }
}
