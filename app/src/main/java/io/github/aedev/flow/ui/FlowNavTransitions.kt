package io.github.aedev.flow.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import io.github.aedev.flow.data.local.PageTransitionStyle

internal object FlowNavTransitions {
    fun enter(style: PageTransitionStyle): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        {
            when (style) {
                PageTransitionStyle.FADE -> {
                    fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                }

                PageTransitionStyle.ZOOM -> {
                    fadeIn(animationSpec = tween(220)) +
                        scaleIn(
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                            initialScale = 0.92f,
                        )
                }

                PageTransitionStyle.RISE -> {
                    fadeIn(animationSpec = tween(220)) +
                        slideInVertically(
                            initialOffsetY = { (it * 0.10f).toInt() },
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                        )
                }

                PageTransitionStyle.SLIDE -> {
                    fadeIn(animationSpec = tween(250, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(
                            initialOffsetX = { (it * 0.06f).toInt() },
                            animationSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                }
            }
        }

    fun exit(style: PageTransitionStyle): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        {
            when (style) {
                PageTransitionStyle.FADE -> {
                    fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing))
                }

                PageTransitionStyle.ZOOM -> {
                    fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                        scaleOut(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            targetScale = 1.06f,
                        )
                }

                PageTransitionStyle.RISE -> {
                    fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                        slideOutVertically(
                            targetOffsetY = { -(it * 0.05f).toInt() },
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                        )
                }

                PageTransitionStyle.SLIDE -> {
                    fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
                }
            }
        }

    fun popEnter(style: PageTransitionStyle): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        {
            when (style) {
                PageTransitionStyle.FADE -> {
                    fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
                }

                PageTransitionStyle.ZOOM -> {
                    fadeIn(animationSpec = tween(220)) +
                        scaleIn(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            initialScale = 1.06f,
                        )
                }

                PageTransitionStyle.RISE -> {
                    fadeIn(animationSpec = tween(220)) +
                        slideInVertically(
                            initialOffsetY = { -(it * 0.05f).toInt() },
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                        )
                }

                PageTransitionStyle.SLIDE -> {
                    fadeIn(animationSpec = tween(250, easing = FastOutSlowInEasing))
                }
            }
        }

    fun popExit(style: PageTransitionStyle): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        {
            when (style) {
                PageTransitionStyle.FADE -> {
                    fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing))
                }

                PageTransitionStyle.ZOOM -> {
                    fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                        scaleOut(
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                            targetScale = 0.92f,
                        )
                }

                PageTransitionStyle.RISE -> {
                    fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                        slideOutVertically(
                            targetOffsetY = { (it * 0.10f).toInt() },
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                        )
                }

                PageTransitionStyle.SLIDE -> {
                    fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)) +
                        slideOutHorizontally(
                            targetOffsetX = { (it * 0.06f).toInt() },
                            animationSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                }
            }
        }

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = enter(PageTransitionStyle.SLIDE)
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = exit(PageTransitionStyle.SLIDE)
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = popEnter(PageTransitionStyle.SLIDE)
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = popExit(PageTransitionStyle.SLIDE)

    // A back gesture seeks these by its progress, so every part shares one linear duration and
    // follows the finger; a spring's open-ended duration let the fades finish a quarter of the way
    // in and left a cancelled gesture's state behind for the next one (#1069).
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = {
        fadeIn(animationSpec = tween(PREDICTIVE_BACK_DURATION_MS, easing = LinearEasing))
    }
    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = {
        fadeOut(animationSpec = tween(PREDICTIVE_BACK_DURATION_MS, easing = LinearEasing)) +
            slideOutHorizontally(
                targetOffsetX = { (it * 0.06f).toInt() },
                animationSpec = tween(PREDICTIVE_BACK_DURATION_MS, easing = LinearEasing),
            )
    }
}

private const val PREDICTIVE_BACK_DURATION_MS = 300
