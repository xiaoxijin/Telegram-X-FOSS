package org.thunderdog.challegram.kuikly.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

/**
 * Kuikly 演示「关于」页：演示页面参数读取（pageData.params）、
 * 条件样式（observable 驱动 attr 重新求值）与 RouterModule.closePage。
 * 平台无关的纯 UI 代码，与 [KuiklyHomePage] 一同构成 HarmonyOS-ready 页面集。
 */
@Page("kuikly_about")
internal class KuiklyAboutPage : Pager() {

  private var fromPage by observable("-")

  private var darkTheme by observable(false)

  override fun created() {
    super.created()
    fromPage = pageData.params.optString("from", "-")
  }

  override fun body(): ViewBuilder {
    val ctx = this
    return {
      attr {
        backgroundColor(if (ctx.darkTheme) Color(0xFF17181A) else Color.WHITE)
        flexDirectionColumn()
      }
      View {
        attr {
          margin(16f)
          flexDirectionColumn()
        }
        Text {
          attr {
            text("关于 Kuikly 页面")
            fontSize(22f)
            fontWeightBold()
            color(if (ctx.darkTheme) Color(0xFFF2F3F5) else Color(0xFF1B1C1E))
          }
        }
        Text {
          attr {
            marginTop(8f)
            text("来源页面：" + ctx.fromPage)
            fontSize(14f)
            color(if (ctx.darkTheme) Color(0xFF9DA2A8) else Color(0xFF8A8E95))
          }
        }
        Text {
          attr {
            marginTop(8f)
            text("本页由 KuiklyUI 声明式 DSL 编写，宿主只负责渲染与适配器桥接。" +
              "页面代码保持平台中立，后续可原样编译到鸿蒙 HarmonyOS NEXT。")
            fontSize(13f)
            color(if (ctx.darkTheme) Color(0xFF9DA2A8) else Color(0xFF8A8E95))
          }
        }
      }
      Button {
        attr {
          margin(16f)
          titleAttr {
            text(if (ctx.darkTheme) "切换到浅色主题" else "切换到深色主题")
            color(Color.WHITE)
          }
          backgroundColor(Color(0xFF339CFF))
          size(280f, 44f)
          borderRadius(10f)
        }
        event {
          click {
            ctx.darkTheme = !ctx.darkTheme
          }
        }
      }
      Button {
        attr {
          margin(16f)
          titleAttr {
            text("返回首页")
            color(Color(0xFF339CFF))
          }
          backgroundColor(Color.WHITE)
          size(280f, 44f)
          borderRadius(10f)
        }
        event {
          click {
            ctx.closeSelf()
          }
        }
      }
    }
  }

  private fun closeSelf() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
  }
}
