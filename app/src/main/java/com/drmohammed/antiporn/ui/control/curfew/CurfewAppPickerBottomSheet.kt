package com.drmohammed.antiporn.ui.control.curfew

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.databinding.BottomSheetCurfewAppPickerBinding
import com.drmohammed.antiporn.databinding.ItemCurfewAppPickerBinding
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.ui.accountability.AppIconResolver
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * نافذة سفلية منبثقة مبسطة لاختيار التطبيقات المستثناة أثناء الحظر الليلي.
 * تتيح تحديد: «تشغيل» (فتح التطبيق) و«نت» (اتصال الإنترنت أثناء عزل الحظر).
 */
class CurfewAppPickerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCurfewAppPickerBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    data class CurfewAppItem(
        val packageName: String,
        val appName: String,
        var isRunAllowed: Boolean,
        var isNetAllowed: Boolean
    )

    private val allApps = mutableListOf<CurfewAppItem>()
    private val filteredApps = mutableListOf<CurfewAppItem>()
    private lateinit var adapter: CurfewAppPickerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCurfewAppPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isRemote = arguments?.getBoolean(ARG_IS_REMOTE) ?: false

        adapter = CurfewAppPickerAdapter(
            isLocalMode = !isRemote,
            onRunToggled = { item, isChecked ->
                item.isRunAllowed = isChecked
                if (!isChecked) {
                    item.isNetAllowed = false
                }
                updateConfirmButton()
                val index = filteredApps.indexOf(item)
                if (index != -1) {
                    adapter.notifyItemChanged(index)
                }
            },
            onNetToggled = { item, isChecked ->
                item.isNetAllowed = isChecked
                if (isChecked) {
                    item.isRunAllowed = true
                }
                updateConfirmButton()
                val index = filteredApps.indexOf(item)
                if (index != -1) {
                    adapter.notifyItemChanged(index)
                }
            }
        )
        adapter.submitItems(filteredApps)

        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = adapter

        binding.etSearchApp.doAfterTextChanged { text ->
            filterApps(text?.toString().orEmpty())
        }

        binding.btnConfirmSelection.setOnClickListener {
            confirmSelection()
        }

        loadApps()
    }

    private fun loadApps() {
        val appContext = context?.applicationContext ?: return
        val isRemote = arguments?.getBoolean(ARG_IS_REMOTE) ?: false
        val wardId = arguments?.getString(ARG_WARD_ID).orEmpty()
        val initialWhitelist = arguments?.getStringArrayList(ARG_INITIAL_WHITELIST)?.toSet() ?: emptySet()
        val initialVpnBypass = arguments?.getStringArrayList(ARG_INITIAL_VPN_BYPASS)?.toSet() ?: emptySet()
        val customBlacklist = arguments?.getStringArrayList(ARG_BLACKLIST)?.toSet() ?: emptySet()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val criticalPackages = CurfewAppResolver.resolveCoreSystemCriticalPackages(appContext)
            val list = mutableListOf<CurfewAppItem>()

            if (!isRemote) {
                // Local Mode
                val installed = AppInventoryManager.getInstance(appContext).getInstalledApps()
                for (app in installed) {
                    val pkg = app.packageName
                    if (isPackageRestricted(pkg, appContext.packageName, criticalPackages, customBlacklist)) continue

                    val isRun = initialWhitelist.contains(pkg)
                    val isNet = isRun && initialVpnBypass.contains(pkg)

                    list.add(
                        CurfewAppItem(
                            packageName = pkg,
                            appName = app.appName.ifBlank { pkg },
                            isRunAllowed = isRun,
                            isNetAllowed = isNet
                        )
                    )
                }
            } else if (wardId.isBlank()) {
                Log.w(TAG, "Refusing remote curfew app load: blank wardId")
                withContext(Dispatchers.Main) {
                    if (isAdded) dismissAllowingStateLoss()
                }
                return@launch
            } else {
                // Remote Mode (Guardian inspecting Ward)
                val firestore = FirebaseFirestore.getInstance()
                var inventoryDoc: DocumentSnapshot? = null
                try {
                    inventoryDoc = firestore.collection("inventories").document(wardId).get().await()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    try {
                        inventoryDoc = firestore.collection("inventories").document(wardId).get(Source.CACHE).await()
                    } catch (cacheEx: Exception) {
                        if (cacheEx is CancellationException) throw cacheEx
                        Log.w(TAG, "Failed to fetch ward inventory from cache: ${cacheEx.message}")
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val rawApps = (inventoryDoc?.get("apps") as? List<*>) ?: emptyList<Any?>()
                val parsedApps = rawApps.mapNotNull { raw ->
                    @Suppress("UNCHECKED_CAST")
                    (raw as? Map<String, Any?>)?.let { InstalledAppDto.fromMap(it) }
                }.filter { it.packageName.isNotBlank() }

                val seenPackages = mutableSetOf<String>()
                for (app in parsedApps) {
                    val pkg = app.packageName
                    if (isPackageRestricted(pkg, appContext.packageName, criticalPackages, customBlacklist)) continue
                    seenPackages.add(pkg)

                    val isRun = initialWhitelist.contains(pkg)
                    val isNet = isRun && initialVpnBypass.contains(pkg)

                    list.add(
                        CurfewAppItem(
                            packageName = pkg,
                            appName = app.appName.ifBlank { pkg },
                            isRunAllowed = isRun,
                            isNetAllowed = isNet
                        )
                    )
                }

                // Append any orphaned whitelisted apps not in inventory
                val extraPackages = (initialWhitelist + initialVpnBypass) - seenPackages
                for (orphan in extraPackages) {
                    if (isPackageRestricted(orphan, appContext.packageName, criticalPackages, customBlacklist)) continue
                    val isRun = initialWhitelist.contains(orphan)
                    val isNet = isRun && initialVpnBypass.contains(orphan)
                    list.add(
                        CurfewAppItem(
                            packageName = orphan,
                            appName = orphan,
                            isRunAllowed = isRun,
                            isNetAllowed = isNet
                        )
                    )
                }
            }

            list.sortBy { it.appName.lowercase() }

            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    allApps.clear()
                    allApps.addAll(list)
                    filterApps(binding.etSearchApp.text?.toString().orEmpty())
                    updateConfirmButton()
                }
            }
        }
    }

    private fun isPackageRestricted(
        pkg: String,
        selfPackageName: String,
        criticalPackages: Set<String>,
        customBlacklist: Set<String>
    ): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == selfPackageName) return true
        if (criticalPackages.contains(pkg)) return true
        if (AppPolicyRegistry.isImmune(pkg)) return true
        if (AppSuspensionShield.isBlacklisted(pkg)) return true
        if (customBlacklist.contains(pkg)) return true
        if (CurfewConfig.KNOWN_APP_STORES.contains(pkg)) return true
        if (CurfewConfig.CURFEW_SEARCH_APPS.contains(pkg)) return true
        return false
    }

    private fun filterApps(query: String) {
        filteredApps.clear()
        if (query.isBlank()) {
            filteredApps.addAll(allApps)
        } else {
            val q = query.trim().lowercase()
            filteredApps.addAll(allApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        adapter.submitItems(filteredApps.toList())
    }

    private fun updateConfirmButton() {
        val count = allApps.count { it.isRunAllowed }
        if (count > 0) {
            binding.btnConfirmSelection.text = getString(R.string.curfew_picker_btn_confirm_count, count)
        } else {
            binding.btnConfirmSelection.text = getString(R.string.curfew_picker_btn_confirm)
        }
    }

    private fun confirmSelection() {
        val newWhitelist = allApps.filter { it.isRunAllowed }.map { it.packageName }.toSet()
        val newVpnBypass = allApps.filter { it.isRunAllowed && it.isNetAllowed }.map { it.packageName }.toSet()

        val resultBundle = Bundle().apply {
            putStringArrayList(RESULT_WHITELIST, ArrayList(newWhitelist))
            putStringArrayList(RESULT_VPN_BYPASS, ArrayList(newVpnBypass))
        }
        parentFragmentManager.setFragmentResult(REQUEST_KEY, resultBundle)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CurfewAppPicker"
        const val REQUEST_KEY = "curfew_app_picker_request"
        const val RESULT_WHITELIST = "result_curfew_whitelist"
        const val RESULT_VPN_BYPASS = "result_curfew_vpn_bypass"

        private const val ARG_IS_REMOTE = "arg_is_remote"
        private const val ARG_WARD_ID = "arg_ward_id"
        private const val ARG_INITIAL_WHITELIST = "arg_initial_whitelist"
        private const val ARG_INITIAL_VPN_BYPASS = "arg_initial_vpn_bypass"
        private const val ARG_BLACKLIST = "arg_blacklist"

        fun newInstance(
            isRemote: Boolean,
            wardId: String,
            initialWhitelist: Set<String>,
            initialVpnBypass: Set<String>,
            blacklist: Set<String>
        ): CurfewAppPickerBottomSheet {
            return CurfewAppPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_REMOTE, isRemote)
                    putString(ARG_WARD_ID, wardId)
                    putStringArrayList(ARG_INITIAL_WHITELIST, ArrayList(initialWhitelist))
                    putStringArrayList(ARG_INITIAL_VPN_BYPASS, ArrayList(initialVpnBypass))
                    putStringArrayList(ARG_BLACKLIST, ArrayList(blacklist))
                }
            }
        }
    }

    class CurfewAppPickerAdapter(
        private val isLocalMode: Boolean,
        private val onRunToggled: (CurfewAppItem, Boolean) -> Unit,
        private val onNetToggled: (CurfewAppItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<CurfewAppPickerAdapter.ViewHolder>() {

        private val items: MutableList<CurfewAppItem> = mutableListOf()

        fun submitItems(newItems: List<CurfewAppItem>) {
            val oldItems = items.toList()
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    oldItems[oldPos].packageName == newItems[newPos].packageName
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    oldItems[oldPos].packageName == newItems[newPos].packageName &&
                        oldItems[oldPos].appName == newItems[newPos].appName
            })
            items.clear()
            items.addAll(newItems)
            diffResult.dispatchUpdatesTo(this)
        }

        class ViewHolder(val binding: ItemCurfewAppPickerBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCurfewAppPickerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            AppIconResolver.cancelLoad(holder.binding.ivAppIcon)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val binding = holder.binding

            binding.tvAppName.text = item.appName
            binding.tvPackageName.text = item.packageName

            AppIconResolver.loadIcon(
                imageView = binding.ivAppIcon,
                packageName = item.packageName,
                appName = item.appName,
                isLocalMode = isLocalMode
            )

            binding.cbRun.setOnCheckedChangeListener(null)
            binding.cbNet.setOnCheckedChangeListener(null)

            binding.cbRun.isChecked = item.isRunAllowed
            binding.cbNet.isChecked = item.isNetAllowed

            // Net is enabled only when Run is allowed
            binding.cbNet.isEnabled = item.isRunAllowed
            binding.cbNet.alpha = if (item.isRunAllowed) 1.0f else 0.45f

            binding.cbRun.setOnCheckedChangeListener { _, isChecked ->
                onRunToggled(item, isChecked)
            }

            binding.cbNet.setOnCheckedChangeListener { _, isChecked ->
                onNetToggled(item, isChecked)
            }

            binding.root.setOnClickListener {
                val newChecked = !binding.cbRun.isChecked
                binding.cbRun.isChecked = newChecked
            }
        }
    }
}
