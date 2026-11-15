package org.thunderdog.challegram.kuikly.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import org.thunderdog.challegram.kuikly.module.SystemPermissionsModule

/**
 * 系统权限页（PORTING_FEATURES.md §E1/E2，UI 用 KuiklyUI 实现，兼作框架验收场）：
 * - 顶部引导卡（§E2）：一次只显示一步 —— 「去打开某一项」→ 去设置 跳系统页；
 *   返回时 pageDidAppear 重新检查，未过显示「再试一次」，全过显示「全部完成」。
 * - 权限状态行（§E1）：每行实时状态，点击跳对应系统页（MIUI 私有路由由宿主处理）。
 * - §E4 文案：明确告知 HyperOS 每次更新都会重新关闭「锁屏显示」。
 */
@Page("kuikly_permissions")
internal class KuiklyPermissionsPage : Pager() {

  private var isMiui by observable(false)

  private var notificationsGranted by observable(true)
  private var autoStartGranted by observable(true)
  private var batteryGranted by observable(true)
  private var lockScreenGranted by observable(true)
  private var backgroundPopupGranted by observable(true)
  private var overlayGranted by observable(true)

  private var stepCount by observable(0)
  private var triedStep by observable(false)

  private var stepIds: ObservableList<Int> by observableList()

  private var rows: ObservableList<Row> by observableList()

  class Row(val id: Int, val title: String)

  override fun created() {
    super.created()
    refresh()
  }

  override fun pageDidAppear() {
    super.pageDidAppear()
    // returning from a system page: re-check so the guide advances (§E2)
    refresh()
  }

  private fun refresh() {
    val module = acquireModule<SystemPermissionsModule>(SystemPermissionsModule.MODULE_NAME)
    val s = module.permissionStates()
    isMiui = s.optBoolean("isMiui", false)
    notificationsGranted = s.optBoolean("1", true)
    autoStartGranted = s.optBoolean("2", true)
    batteryGranted = s.optBoolean("3", true)
    lockScreenGranted = s.optBoolean("4", true)
    backgroundPopupGranted = s.optBoolean("5", true)
    overlayGranted = s.optBoolean("6", true)

    val guide = module.permissionGuide()
    stepCount = guide.optInt("stepCount", 0)
    triedStep = stepCount > 0
    stepIds.clear()
    val step = guide.optJSONArray("step")
    if (step != null) {
      for (i in 0 until step.length()) {
        stepIds.add(step.optInt(i))
      }
    }

    rows.clear()
    // §E4 (commit 27bf780ba): background call permissions come first on MIUI/HyperOS —
    // autostart and unrestricted battery are what keeps calls and pushes alive
    if (isMiui) {
      rows.add(Row(ID_AUTO_START, "自启动"))
      rows.add(Row(ID_BATTERY, "电池不限"))
    }
    rows.add(Row(ID_NOTIFICATIONS, "通知"))
    if (isMiui) {
      rows.add(Row(ID_LOCK_SCREEN, "锁屏显示"))
      rows.add(Row(ID_BACKGROUND_POPUP, "后台弹出"))
    }
    rows.add(Row(ID_OVERLAY, "悬浮窗"))
  }

  private fun granted(id: Int): Boolean = when (id) {
    ID_NOTIFICATIONS -> notificationsGranted
    ID_AUTO_START -> autoStartGranted
    ID_BATTERY -> batteryGranted
    ID_LOCK_SCREEN -> lockScreenGranted
    ID_BACKGROUND_POPUP -> backgroundPopupGranted
    ID_OVERLAY -> overlayGranted
    else -> true
  }

  private fun permissionName(id: Int): String = when (id) {
    ID_NOTIFICATIONS -> "通知"
    ID_AUTO_START -> "自启动"
    ID_BATTERY -> "电池不限"
    ID_LOCK_SCREEN -> "锁屏显示"
    ID_BACKGROUND_POPUP -> "后台弹出"
    ID_OVERLAY -> "悬浮窗"
    else -> "全屏通知"
  }

  /** Exactly what to tap on the page the step opens. */
  private fun stepHow(): String = when (stepIds.firstOrNull() ?: 0) {
    ID_NOTIFICATIONS -> "在通知页打开「允许通知」"
    ID_AUTO_START -> "打开「自启动」开关"
    ID_BATTERY -> if (isMiui) "选择「无限制」" else "把本应用加入不受限制列表"
    ID_OVERLAY -> "打开「允许显示在其他应用上层」"
    else -> {
      val names = StringBuilder()
      for (id in stepIds) {
        names.append("「").append(permissionName(id)).append("」")
      }
      "在权限管理页里逐个打开 $names"
    }
  }

  override fun body(): ViewBuilder {
    val ctx = this
    return {
      attr {
        backgroundColor(Color(0xFFF4F5F7))
        flexDirectionColumn()
      }

      // ── guide card (§E2): one step at a time ──
      vif({ ctx.stepCount > 0 && ctx.stepIds.isNotEmpty() }) {
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
              text("权限引导：还差 " + ctx.stepCount + " 步")
              fontSize(17f)
              fontWeightBold()
              color(Color(0xFF1B1C1E))
            }
          }
          Text {
            attr {
              margin(16f)
              text("去打开：" + ctx.stepNames())
              fontSize(15f)
              color(Color(0xFF3C3F43))
            }
          }
          Text {
            attr {
              marginLeft(16f)
              marginRight(16f)
              marginBottom(16f)
              text(if (ctx.triedStep) "打开后回来这里会自动复查；还没过的话，再试一次：" else "怎么改：" + ctx.stepHow())
              fontSize(13f)
              color(Color(0xFF8A8E95))
            }
          }
          Button {
            attr {
              margin(16f)
              titleAttr {
                text("去设置")
                color(Color.WHITE)
              }
              backgroundColor(Color(0xFF339CFF))
              size(280f, 44f)
              borderRadius(10f)
            }
            event {
              click {
                ctx.openStep()
              }
            }
          }
        }
      }
      vif({ ctx.stepCount == 0 }) {
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
              text("全部完成 ✓")
              fontSize(17f)
              fontWeightBold()
              color(Color(0xFF1B1C1E))
            }
          }
          Text {
            attr {
              marginLeft(16f)
              marginRight(16f)
              marginBottom(16f)
              text("消息与来电所需的所有系统权限都已打开。")
              fontSize(13f)
              color(Color(0xFF8A8E95))
            }
          }
        }
      }

      // ── status rows (§E1): real-time state, tap to jump ──
      View {
        attr {
          margin(16f)
          backgroundColor(Color.WHITE)
          borderRadius(12f)
          flexDirectionColumn()
        }
        vfor({ ctx.rows }) { row ->
          View {
            attr {
              flexDirectionRow()
              alignItemsCenter()
              height(48f)
            }
            event {
              click {
                ctx.openSettings(row.id)
              }
            }
            Text {
              attr {
                marginLeft(16f)
                flex(1f)
                text(row.title)
                fontSize(15f)
                color(Color(0xFF1B1C1E))
              }
            }
            Text {
              attr {
                marginRight(16f)
                text(if (ctx.granted(row.id)) "已开启" else "未开启")
                fontSize(13f)
                color(if (ctx.granted(row.id)) Color(0xFF34C759) else Color(0xFFFF3B30))
              }
            }
          }
        }
      }

      // ── §E4 notice ──
      Text {
        attr {
          margin(16f)
          text("注意：HyperOS 每次系统或应用更新后，都可能重新关闭「锁屏显示」，来电无法亮屏时请回本页复查。")
          fontSize(12f)
          color(Color(0xFF8A8E95))
        }
      }
    }
  }

  private fun stepNames(): String {
    val names = StringBuilder()
    for (id in stepIds) {
      names.append(if (names.isEmpty()) "" else "、").append(permissionName(id))
    }
    return names.toString()
  }

  private fun openStep() {
    val id = stepIds.firstOrNull() ?: return
    openSettings(id)
  }

  private fun openSettings(id: Int) {
    acquireModule<SystemPermissionsModule>(SystemPermissionsModule.MODULE_NAME).openSettings(id)
  }

  companion object {
    // mirrors SystemPermissionsHelper ids (kuikly-pages android side)
    const val ID_NOTIFICATIONS = 1
    const val ID_AUTO_START = 2
    const val ID_BATTERY = 3
    const val ID_LOCK_SCREEN = 4
    const val ID_BACKGROUND_POPUP = 5
    const val ID_OVERLAY = 6
    const val ID_FULL_SCREEN = 7
  }
}
