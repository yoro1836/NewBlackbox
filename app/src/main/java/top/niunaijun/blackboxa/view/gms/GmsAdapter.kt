package top.niunaijun.blackboxa.view.gms

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.databinding.ItemGmsBinding


class GmsAdapter(
    private val onSettings: (GmsBean) -> Unit,
    private val onSignOut: (GmsBean) -> Unit
) : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return GmsVH(inflate(R.layout.item_gms,parent), onSettings, onSignOut)
    }


    class GmsVH(
        itemView:View,
        private val onSettings: (GmsBean) -> Unit,
        private val onSignOut: (GmsBean) -> Unit
    ):RVHolder<GmsBean>(itemView){

        private val binding = ItemGmsBinding.bind(itemView)
        override fun setContent(item: GmsBean, isSelected: Boolean, payload: Any?) {
            binding.tvTitle.text = item.userName
            binding.checkbox.isChecked = item.isInstalledGms
            binding.tvKind.visibility = if (item.isInstalledGms) View.VISIBLE else View.GONE
            binding.tvKind.setText(if (item.isMicroG) R.string.gms_kind_microg else R.string.gms_kind_host)
            binding.btnSettings.visibility =
                if (item.isInstalledGms && item.isMicroG) View.VISIBLE else View.GONE
            binding.btnSettings.setOnClickListener { onSettings(item) }
            binding.btnSignOut.visibility = if (item.isInstalledGms) View.VISIBLE else View.GONE
            binding.btnSignOut.setOnClickListener { onSignOut(item) }
            binding.checkbox.setOnCheckedChangeListener  { buttonView, _ ->
                if(buttonView.isPressed){
                    binding.root.performClick()
                }
            }
        }
    }
}
