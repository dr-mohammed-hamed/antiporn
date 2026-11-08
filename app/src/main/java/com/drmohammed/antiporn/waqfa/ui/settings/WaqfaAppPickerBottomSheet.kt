package com.drmohammed.antiporn.waqfa.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.databinding.BottomSheetWaqfaAppPickerBinding
import com.drmohammed.antiporn.databinding.ItemWaqfaAppPickerBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * نافذة سفلية منبثقة لاختيار التطبيقات الخاضعة لنظام «وَقْفَة».
 */
class WaqfaAppPickerBottomSheet(
    private val initiallySelected: Set<String>,
    private val onConfirmed: (Set<String>) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetWaqfaAppPickerBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    data class AppItem(
        val packageName: String,
        val appName: String,
        val icon: Drawable?,
        var isSelected: Boolean
    )

    private val allApps = mutableListOf<AppItem>()
    private val filteredApps = mutableListOf<AppItem>()
    private lateinit var adapter: AppPickerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetWaqfaAppPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AppPickerAdapter(filteredApps) { item ->
            item.isSelected = !item.isSelected
            adapter.notifyItemChanged(filteredApps.indexOf(item))
            updateConfirmButton()
        }

        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = adapter

        binding.etSearchApp.doAfterTextChanged { text ->
            filterApps(text?.toString().orEmpty())
        }

        binding.btnConfirmSelection.setOnClickListener {
            val selected = allApps.filter { it.isSelected }.map { it.packageName }.toSet()
            onConfirmed(selected)
            dismiss()
        }

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val pm = appContext.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val criticalPackages = CurfewAppResolver.resolveCoreSystemCriticalPackages(appContext)
            val activities = pm.queryIntentActivities(intent, 0)
            val list = mutableListOf<AppItem>()
            val seen = mutableSetOf<String>()

            for (resolveInfo in activities) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == appContext.packageName || seen.contains(pkg) || AppPolicyRegistry.isImmune(pkg) || criticalPackages.contains(pkg)) {
                    continue
                }
                seen.add(pkg)

                val name = resolveInfo.loadLabel(pm).toString()
                val icon = try {
                    resolveInfo.loadIcon(pm)
                } catch (e: Exception) {
                    android.util.Log.d("WaqfaAppPicker", "Could not load icon for $pkg", e)
                    null
                }

                list.add(
                    AppItem(
                        packageName = pkg,
                        appName = name,
                        icon = icon,
                        isSelected = initiallySelected.contains(pkg)
                    )
                )
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
        adapter.notifyDataSetChanged()
    }

    private fun updateConfirmButton() {
        val count = allApps.count { it.isSelected }
        if (count > 0) {
            binding.btnConfirmSelection.text = getString(R.string.waqfa_btn_confirm_selection_count, count)
        } else {
            binding.btnConfirmSelection.text = getString(R.string.waqfa_btn_confirm_selection)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class AppPickerAdapter(
        private val items: List<AppItem>,
        private val onItemClick: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.AppViewHolder>() {

        inner class AppViewHolder(val itemBinding: ItemWaqfaAppPickerBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val b = ItemWaqfaAppPickerBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return AppViewHolder(b)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val item = items[position]
            holder.itemBinding.tvAppName.text = item.appName
            holder.itemBinding.tvPackageName.text = item.packageName
            if (item.icon != null) {
                holder.itemBinding.ivAppIcon.setImageDrawable(item.icon)
            } else {
                holder.itemBinding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            holder.itemBinding.cbSelected.isChecked = item.isSelected

            holder.itemBinding.root.setOnClickListener {
                onItemClick(item)
            }
            holder.itemBinding.cbSelected.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
