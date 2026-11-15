package org.thunderdog.challegram.kuikly.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 「TG PRO membership」页（PORTING_FEATURES.md §C2）：宿主的 "TG PRO membership"
 * 设置行打开本页。定制链接（custom.pro_link）由宿主经 pageData.params 传入，
 * 为空时宿主直接隐藏入口，本页不会被打开。
 * 平台中立纯 UI 代码（仅 com.tencent.kuikly.core.*），HarmonyOS-ready。
 */
@Page("kuikly_pro")
internal class KuiklyProPage : Pager() {

  private var proLink by observable("-")

  override fun created() {
    super.created()
    proLink = pageData.params.optString("pro_link", "-")
  }

  override fun body(): ViewBuilder {
    val ctx = this
    return {
      attr {
        backgroundColor(Color(0xFFF4F5F7))
        flexDirectionColumn()
      }
      View {
        attr {
          margin(16f)
          backgroundColor(Color.WHITE)
          borderRadius(12f)
          flexDirectionColumn()
        }
        Text {
          attr {
            margin(16f)
            text("TG PRO membership")
            fontSize(22f)
            fontWeightBold()
            color(Color(0xFF1B1C1E))
          }
        }
        Text {
          attr {
            margin(16f)
            text("本构建的会员服务由发行方独立提供，不经过任何应用商店内购。" +
              "详情与购买入口见下方链接。")
            fontSize(14f)
            color(Color(0xFF3C3F43))
          }
        }
        Text {
          attr {
            margin(16f)
            marginBottom(24f)
            text(ctx.proLink)
            fontSize(13f)
            color(Color(0xFF339CFF))
          }
        }
      }
    }
  }
}
