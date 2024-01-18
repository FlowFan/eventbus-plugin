package com.github.flowfan.eventbusplugin.providers

import com.github.flowfan.eventbusplugin.getCallExpression
import com.github.flowfan.eventbusplugin.getParentOfTypeCallExpression
import com.github.flowfan.eventbusplugin.search
import com.github.flowfan.eventbusplugin.showPostUsages
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionStatement
import com.intellij.ui.awt.RelativePoint
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement

class PostLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val uElement = element.toUElement() ?: return null
        if (element !is PsiExpressionStatement) {
            val uCallExpression = uElement.getCallExpression()
            if (uCallExpression != null && uCallExpression.isPost()) {
                val psiIdentifier = uCallExpression.methodIdentifier?.sourcePsi ?: return null
                return PostLineMarkerInfo(psiIdentifier)
            }
        }
        return null
    }
}

internal fun UsageInfo.isPost(): Boolean {
    val uElement = element.toUElement()
    if (uElement != null) {
        return if (uElement is UCallExpression) {
            uElement.isPost()
        } else {
            uElement.getParentOfTypeCallExpression()?.isPost() == true
        }
    }
    return false
}

private fun UCallExpression.isPost(): Boolean {
    return receiverType?.canonicalText == "org.greenrobot.eventbus.EventBus"
            && (methodName == "post" || methodName == "postSticky")
            && valueArgumentCount == 1
}

private class PostLineMarkerInfo(
        psiElement: PsiElement
) : LineMarkerInfo<PsiElement>(
        psiElement,
        psiElement.textRange,
        IconLoader.getIcon("/META-INF/icon.svg", PostLineMarkerInfo::class.java),
        null,
        { event, element ->
            ReadAction.nonBlocking<Unit> {
                var usages = emptyList<UsageInfo2UsageAdapter>()
                val uElement = element.toUElement()?.getParentOfType<UCallExpression>()
                if (uElement != null) {
                    val argument = uElement.valueArguments.firstOrNull()
                    val resolve = (argument?.getExpressionType() as PsiClassType).resolve()
                    if (resolve != null) {
                        val collection = search(resolve)
                        usages = collection
                                .filter(UsageInfo::isSubscribe)
                                .map(::UsageInfo2UsageAdapter)
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    if (usages.size == 1) {
                        usages.first().navigate(true)
                    } else {
                        showPostUsages(usages, RelativePoint(event))
                    }
                }
            }.inSmartMode(element.project).submit(AppExecutorUtil.getAppExecutorService())
        },
        GutterIconRenderer.Alignment.LEFT,
        { "Find usages of this event" }
)