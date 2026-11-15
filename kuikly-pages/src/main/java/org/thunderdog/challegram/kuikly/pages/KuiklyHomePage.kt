package org.thunderdog.challegram.kuikly.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

/**
 * Kuikly 演示首页。
 *
 * 纯 UI 层、平台无关代码：仅依赖 `com.tencent.kuikly.core.*`，
 * 同一份源码可编译到 Android（core-render-android）与
 * 鸿蒙 HarmonyOS NEXT（core-render-ohos，经 KN 产出 libshared.so）。
 * 语法规范见 `.claude/rules/kuiklyDSL.mdc`。
 */
@Page("kuikly_home")
internal class KuiklyHomePage : Pager() {

  private var tapCount by observable(0)

  private var featureList: ObservableList<String> by observableList()

  override fun created() {
    super.created()
    featureList.addAll(
      listOf(
        "响应式 UI：observable 驱动 attr 自动更新",
        "FlexBox 跨端布局",
        "vfor / vif 指令渲染列表与条件视图",
        "RouterModule 跨端页面路由",
        "同一份代码编译到 Android 与 HarmonyOS NEXT"
      )
    )
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
          flexDirectionColumn()
        }
        Text {
          attr {
            text("Kuikly UI")
            fontSize(26f)
            fontWeightBold()
            color(Color(0xFF1B1C1E))
          }
        }
        Text {
          attr {
            marginTop(6f)
            text("KuiklyUI 渲染的界面 · UI 层跨端改造演示")
            fontSize(13f)
            color(Color(0xFF8A8E95))
          }
        }
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
            text("已点击 " + ctx.tapCount + " 次")
            fontSize(15f)
            color(Color(0xFF1B1C1E))
          }
        }
        Button {
          attr {
            margin(16f)
            titleAttr {
              text("点我 +1")
              color(Color.WHITE)
            }
            backgroundColor(Color(0xFF339CFF))
            size(280f, 44f)
            borderRadius(10f)
          }
          event {
            click {
              ctx.tapCount++
            }
          }
        }
      }
      View {
        attr {
          margin(16f)
          backgroundColor(Color.WHITE)
          borderRadius(12f)
          flexDirectionColumn()
        }
        vfor({ ctx.featureList }) { feature ->
          Text {
            attr {
              margin(16f)
              text(feature)
              fontSize(14f)
              color(Color(0xFF3C3F43))
            }
          }
        }
      }
      Button {
        attr {
          margin(16f)
          titleAttr {
            text("打开关于页")
            color(Color(0xFF339CFF))
          }
          backgroundColor(Color.WHITE)
          size(280f, 44f)
          borderRadius(10f)
        }
        event {
          click {
            ctx.openAboutPage()
          }
        }
      }
    }
  }

  private fun openAboutPage() {
    val params = JSONObject()
    params.put("from", "kuikly_home")
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
      .openPage("kuikly_about", params)
  }
}
