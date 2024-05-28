package ir.ehsannarmani.compose_charts

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import ir.ehsannarmani.compose_charts.components.RCChartLabelHelper
import ir.ehsannarmani.compose_charts.extensions.addRoundRect
import ir.ehsannarmani.compose_charts.extensions.drawGridLines
import ir.ehsannarmani.compose_charts.extensions.spaceBetween
import ir.ehsannarmani.compose_charts.extensions.split
import ir.ehsannarmani.compose_charts.models.AnimationMode
import ir.ehsannarmani.compose_charts.models.BarProperties
import ir.ehsannarmani.compose_charts.models.Bars
import ir.ehsannarmani.compose_charts.models.DividerProperties
import ir.ehsannarmani.compose_charts.models.GridProperties
import ir.ehsannarmani.compose_charts.models.IndicatorProperties
import ir.ehsannarmani.compose_charts.models.LabelHelperProperties
import ir.ehsannarmani.compose_charts.models.LabelProperties
import ir.ehsannarmani.compose_charts.models.PopupProperties
import ir.ehsannarmani.compose_charts.models.SelectedBar
import ir.ehsannarmani.compose_charts.utils.ImplementRCAnimation
import ir.ehsannarmani.compose_charts.utils.calculateOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.max

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ColumnChart(
    modifier: Modifier = Modifier,
    data: List<Bars>,
    barProperties: BarProperties = BarProperties(),
    labelProperties: LabelProperties = LabelProperties(
        textStyle = TextStyle.Default,
        enabled = true
    ),
    indicatorProperties: IndicatorProperties = IndicatorProperties(textStyle = TextStyle.Default),
    dividerProperties: DividerProperties = DividerProperties(),
    gridProperties: GridProperties = GridProperties(),
    labelHelperProperties: LabelHelperProperties = LabelHelperProperties(),
    animationMode: AnimationMode = AnimationMode.Together(),
    animationSpec: AnimationSpec<Float> = snap(),
    animationDelay: Long = 200,
    textMeasurer: TextMeasurer = rememberTextMeasurer(),
    popupProperties: PopupProperties = PopupProperties(
        textStyle = TextStyle.Default.copy(
            color = Color.White,
            fontSize = 12.sp
        )
    ),
    barAlphaDecreaseOnPopup: Float = .4f,
    maxValue: Double = data.maxOfOrNull { it.values.maxOfOrNull { it.value } ?: 0.0 } ?: 0.0,
    minValue: Double = if (data.any { it.values.any { it.value < 0 } }) -maxValue else 0.0
) {
    require(data.isNotEmpty()) {
        "Chart data is empty"
    }
    require(maxValue >= data.maxOf { it.values.maxOf { it.value } }) {
        "Chart data must be at most $maxValue (Specified Max Value)"
    }
    require(minValue <= 0) {
        "Min value in column chart must be 0 or lower."
    }
    require(minValue <= data.minOf { it.values.minOf { it.value } }){
        "Chart data must be at least $minValue (Specified Min Value)"
    }

    val density = LocalDensity.current

    val everyDataWidth = with(density) {
        data.map { rowData ->
            rowData.values.map {
                (it.properties?.thickness
                    ?: barProperties.thickness).toPx() + (it.properties?.spacing
                    ?: barProperties.spacing).toPx()
            }.sum()
        }.average().toFloat()
    }

    val rectWithValue = remember {
        mutableStateListOf<Pair<Double, Rect>>()
    }

    val selectedValue = remember {
        mutableStateOf<SelectedBar?>(null)
    }

    val popupAnimation = remember {
        Animatable(0f)
    }

    val indicators = remember {
        maxValue.split(
            step = (maxValue - minValue) / indicatorProperties.count,
            minValue = minValue
        )
    }
    val indicatorAreaWidth = remember {
        if (indicatorProperties.enabled) {
            indicators.maxOf { textMeasurer.measure(indicatorProperties.contentBuilder(it)).size.width } + (16 * density.density)
        } else {
            0f
        }
    }

    LaunchedEffect(selectedValue.value) {
        if (selectedValue.value != null) {
            delay(popupProperties.duration)
            popupAnimation.animateTo(0f, animationSpec = popupProperties.animationSpec)
            selectedValue.value = null
        }
    }

    ImplementRCAnimation(
        data = data,
        animationMode = animationMode,
        spec = { it.animationSpec ?: animationSpec },
        delay = animationDelay,
        before = {
            rectWithValue.clear()
        }
    )
    Column(modifier = modifier) {
        if (labelHelperProperties.enabled) {
            RCChartLabelHelper(data = data, textStyle = labelHelperProperties.textStyle)
            Spacer(modifier = Modifier.height(24.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            val scope = rememberCoroutineScope()
            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    if (!popupProperties.enabled) return@pointerInput
                    detectDragGestures { change, dragAmount ->
                        rectWithValue
                            .lastOrNull { (value,rect)->
                                change.position.x in rect.left .. rect.right
                            }
                            ?.let {(value,rect)->
                                selectedValue.value = SelectedBar(
                                    value = value,
                                    rect = rect,
                                    offset = Offset(rect.left, if (value < 0) rect.bottom else rect.top)
                                )
                                scope.launch {
                                    if (popupAnimation.value != 1f && !popupAnimation.isRunning) {
                                        popupAnimation.animateTo(
                                            1f,
                                            animationSpec = popupProperties.animationSpec
                                        )
                                    }
                                }
                            }
                    }
                }
                .pointerInteropFilter { event ->
                    if (event.action == MotionEvent.ACTION_DOWN && popupProperties.enabled) {
                        val position = Offset(event.x, event.y)
                        rectWithValue
                            .lastOrNull {
                                it.second.contains(position)
                            }
                            ?.let {(value,rect)->
                                selectedValue.value = SelectedBar(
                                    value = value,
                                    rect = rect,
                                    offset = Offset(rect.left, if (value < 0) rect.bottom else rect.top)
                                )
                                scope.launch {
                                    popupAnimation.snapTo(0f)
                                    popupAnimation.animateTo(
                                        1f,
                                        animationSpec = popupProperties.animationSpec
                                    )
                                }
                            }
                    }
                    false
                }) {

                val barsAreaWidth = size.width - (indicatorAreaWidth)
                val zeroY = size.height - calculateOffset(
                    maxValue = maxValue.toFloat(),
                    minValue = minValue.toFloat(),
                    total = size.height,
                    value = 0.0f
                )


                if (indicatorProperties.enabled) {
                    indicators.forEachIndexed { index, indicator ->
                        val measureResult =
                            textMeasurer.measure(
                                indicatorProperties.contentBuilder(indicator),
                                style = indicatorProperties.textStyle
                            )
                        drawText(
                            textLayoutResult = measureResult,
                            topLeft = Offset(
                                x = 0f,
                                y = (size.height - measureResult.size.height).spaceBetween(
                                    itemCount = indicators.count(),
                                    index
                                )
                            )
                        )
                    }
                }

                drawGridLines(
                    xPadding = indicatorAreaWidth,
                    size = size.copy(width = barsAreaWidth),
                    dividersProperties = dividerProperties,
                    xAxisProperties = gridProperties.xAxisProperties,
                    yAxisProperties = gridProperties.yAxisProperties,
                    gridEnabled = gridProperties.enabled
                )

                data.forEachIndexed { dataIndex, columnChart ->
                    columnChart.values.forEachIndexed { valueIndex, col ->
                        val stroke = (col.properties?.thickness ?: barProperties.thickness).toPx()
                        val spacing = (col.properties?.spacing ?: barProperties.spacing).toPx()

                        val barHeight =
                            ((col.value * size.height) / (maxValue - minValue)) * col.animator.value
                        val everyBarWidth = (stroke + spacing)


                        val barX = (valueIndex * everyBarWidth) + (barsAreaWidth - everyDataWidth).spaceBetween(
                            itemCount = data.count(),
                            index = dataIndex
                        ) + indicatorAreaWidth
                        val rect = Rect(
                            offset = Offset(
                                x = barX,
                                y = (zeroY - barHeight.toFloat().coerceAtLeast(0f))
                            ),
                            size = Size(width = stroke, height = barHeight.absoluteValue.toFloat()),
                        )
                        if (rectWithValue.none { it.second == rect }) rectWithValue.add(col.value to rect)
                        val path = Path()

                        var radius = (col.properties?.cornerRadius ?: barProperties.cornerRadius)
                        if (col.value < 0){
                            radius = radius.reverse()
                        }

                        path.addRoundRect(rect = rect, radius = radius)
                        val alpha = if (rect == selectedValue.value?.rect) {
                            1f - (barAlphaDecreaseOnPopup * popupAnimation.value)
                        } else {
                            1f
                        }
                        drawPath(
                            path = path,
                            brush = col.color,
                            alpha = alpha,
                            style = (col.properties?.style
                                ?: barProperties.style).getStyle(density.density)
                        )
                    }
                }
                if (selectedValue.value != null) {
                    val measure = textMeasurer.measure(
                        popupProperties.contentBuilder(selectedValue.value!!.value),
                        style = popupProperties.textStyle.copy(
                            color = popupProperties.textStyle.color.copy(
                                alpha = popupAnimation.value * 1f
                            )
                        )
                    )

                    val textSize = measure.size.toSize()
                    val popupSize = Size(
                        width = (textSize.width + (popupProperties.contentHorizontalPadding.toPx() * 2)) ,
                        height = textSize.height + popupProperties.contentVerticalPadding.toPx() * 2
                    )
                    val value = selectedValue.value!!.value
                    val barRect = selectedValue.value!!.rect
                    val barWidth = barRect.right-barRect.left
                    val barHeight = barRect.bottom-barRect.top
                    var popupPosition = selectedValue.value!!.offset.copy(
                        y = selectedValue.value!!.offset.y - popupSize.height + barHeight/10,
                        x = selectedValue.value!!.offset.x + barWidth/ 2
                    )
                    if (value < 0){
                        popupPosition = popupPosition.copy(
                            y= selectedValue.value!!.offset.y - barHeight/10
                        )
                    }
                    val outOfCanvas = popupPosition.x+popupSize.width > size.width
                    if (outOfCanvas){
                       popupPosition = popupPosition.copy(
                           x = (selectedValue.value!!.offset.x - popupSize.width)+ barWidth/ 2
                       )
                    }
                    val cornerRadius =
                        CornerRadius(
                            popupProperties.cornerRadius.toPx(),
                            popupProperties.cornerRadius.toPx()
                        )
                    drawPath(
                        path = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(
                                        offset = popupPosition,
                                        size = popupSize.copy(
                                            width = popupSize.width*popupAnimation.value
                                        ),
                                    ),
                                    topRight = if (selectedValue.value!!.value < 0 && outOfCanvas) CornerRadius.Zero else cornerRadius,
                                    topLeft = if (selectedValue.value!!.value < 0 && !outOfCanvas) CornerRadius.Zero else cornerRadius ,
                                    bottomRight = if (selectedValue.value!!.value > 0 && outOfCanvas) CornerRadius.Zero else cornerRadius,
                                    bottomLeft = if (selectedValue.value!!.value > 0 && !outOfCanvas) CornerRadius.Zero else cornerRadius
                                )
                            )
                        },
                        color = popupProperties.containerColor,
                    )
                    drawText(
                        textLayoutResult = measure,
                        topLeft = popupPosition.copy(
                            x = popupPosition.x + popupProperties.contentHorizontalPadding.toPx(),
                            y = popupPosition.y + popupProperties.contentVerticalPadding.toPx()
                        ),
                    )
                }

            }
        }
        if (labelProperties.enabled) {
            Spacer(modifier = Modifier.height(labelProperties.padding))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = (indicatorAreaWidth / density.density).dp,
                    ), horizontalArrangement = Arrangement.SpaceBetween
            ) {
                data.forEach {
                    BasicText(
                        text = it.label,
                        style = labelProperties.textStyle,
                    )
                }
            }
        }

    }
}
