package com.meihaoshiguang.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(248, 242, 230)
        window.navigationBarColor = android.graphics.Color.rgb(248, 242, 230)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                    GameScreen()
                }
            }
        }
    }
}

private object GameConfig {
    const val START_ROUNDS = 3
    const val START_MOVES = 10
    const val START_SIZE = 8
    const val START_TARGET = 1200
    const val ENERGY_TO_SKILL = 12
    const val NEUTRAL_CAP_RATIO = .20

    fun rounds(level: Int) = START_ROUNDS + (level - 1) / 3
    fun moves(level: Int) = (START_MOVES - (level - 1) / 4).coerceAtLeast(6)
    fun boardSize(level: Int) = (START_SIZE + (level - 1) / 4).coerceAtMost(10)
    fun target(level: Int) = START_TARGET + (level - 1) * 300
}

private data class Fruit(val id: Int, val color: Color, val character: Int? = null)
private data class FruitPiece(val uid: Long, val type: Int)
private var nextFruitUid = 1L
private fun newPiece(type: Int) = FruitPiece(nextFruitUid++, type)
private val EmptyPiece = FruitPiece(-1L, -1)
private val fruitTypes = listOf(
    Fruit(0, rgb(227, 173, 185), 0),
    Fruit(1, rgb(0, 47, 167), 1),
    Fruit(2, rgb(139, 163, 199), 2),
    Fruit(3, rgb(229, 168, 75), 3),
    Fruit(4, rgb(164, 190, 142)),
    Fruit(5, rgb(174, 143, 184))
)
private val characterNames = listOf("桃桃", "蓝莓", "云朵", "柚子")
private val skills = listOf("果色蔓延", "三列清扫", "灵感加步", "杂色清理")

private data class GameState(
    val level: Int = 1,
    val score: Int = 0,
    val round: Int = 1,
    val movesLeft: Int = GameConfig.START_MOVES,
    val board: List<FruitPiece> = emptyList(),
    val fallingIds: Set<Long> = emptySet(),
    val energy: List<Int> = List(4) { 0 },
    val neutralAdded: Int = 0,
    val selected: Int? = null,
    val isPaused: Boolean = false,
    val highlightIndices: Set<Int> = emptySet(),
    val highlightMoves: Boolean = false,
    val highlightToken: Int = 0,
    val status: GameStatus = GameStatus.Playing,
    val message: String = "交换相邻水果，收集角色能量！"
)
private enum class GameStatus { Playing, Won, Lost }
@Composable
private fun GameScreen() {
    var game by remember { mutableStateOf(newGame(1)) }
    val configSize = GameConfig.boardSize(game.level)
    val roundLimit = GameConfig.rounds(game.level)
    val roundMoves = GameConfig.moves(game.level)
    val target = GameConfig.target(game.level)
    var displayBoard by remember { mutableStateOf(game.board) }
    var newPieceIds by remember { mutableStateOf(emptySet<Long>()) }
    var boardAnimating by remember { mutableStateOf(false) }
    var swapAnimationJob by remember { mutableStateOf<Job?>(null) }
    val animationScope = rememberCoroutineScope()

    fun resetGame(level: Int) {
        swapAnimationJob?.cancel()
        boardAnimating = false
        val newState = newGame(level)
        game = newState
        displayBoard = newState.board
        newPieceIds = emptySet()
    }

    fun animateSwap(first: Int, second: Int) {
        if (boardAnimating || game.status != GameStatus.Playing || game.isPaused) return
        val startingState = game
        val swappedBoard = startingState.board.toMutableList().apply {
            val value = this[first]
            this[first] = this[second]
            this[second] = value
        }
        val resolvedState = swapAndResolve(startingState, first, second, configSize)
        boardAnimating = true
        game = startingState.copy(selected = null)
        displayBoard = swappedBoard
        newPieceIds = emptySet()
        swapAnimationJob = animationScope.launch {
            delay(260)
            if (resolvedState.score == startingState.score) {
                displayBoard = startingState.board
                delay(220)
                game = resolvedState
            } else {
                val next = finishMove(resolvedState, roundLimit, target)
                game = next
                displayBoard = next.board
                newPieceIds = next.fallingIds
                delay(520)
                newPieceIds = emptySet()
            }
            boardAnimating = false
        }
    }

    LaunchedEffect(game.highlightToken) {
        if (game.highlightToken > 0 && (game.highlightIndices.isNotEmpty() || game.highlightMoves)) {
            delay(1400)
            game = game.copy(highlightIndices = emptySet(), highlightMoves = false)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Paper)) {
        val compact = maxHeight < 760.dp
        Column(
            Modifier.fillMaxSize()
                .padding(start = 18.dp, end = 18.dp, top = 50.dp, bottom = if (compact) 8.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(
                game.level,
                isPaused = game.isPaused,
                onPauseToggle = { game = game.copy(isPaused = !game.isPaused) },
                onRestart = { resetGame(game.level) }
            )
            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
            ScoreAndRounds(game, target, roundLimit)
            Spacer(Modifier.height(if (compact) 7.dp else 12.dp))
            MovesCard(game.movesLeft, roundMoves, game.round, roundLimit, game.highlightMoves)
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Text(game.message, color = InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            CollageBoard(
                board = displayBoard,
                size = configSize,
                selected = game.selected,
                highlighted = game.highlightIndices,
                enabled = game.status == GameStatus.Playing && !game.isPaused && !boardAnimating,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f),
                newPieceIds = newPieceIds,
                onSwipeSwap = ::animateSwap,
                onTap = { index ->
                    if (game.status != GameStatus.Playing) return@CollageBoard
                    val first = game.selected
                    if (first == null || first == index) game = game.copy(selected = if (first == index) null else index)
                    else if (areAdjacent(first, index, configSize)) {
                        animateSwap(first, index)
                    } else game = game.copy(selected = index)
                }
            )
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            CharacterSkills(game, enabled = !boardAnimating, onUse = { id ->
                val next = useSkill(game, id, configSize)
                game = if (next.score >= target) next.copy(status = GameStatus.Won, message = "目标达成！") else next
                displayBoard = next.board
                newPieceIds = next.fallingIds
                animationScope.launch { delay(520); newPieceIds = emptySet() }
            })
            Spacer(Modifier.height(5.dp))
            Text("一页果园手账  ·  慢慢玩，也很甜", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        }
        if (game.status != GameStatus.Playing) {
            ResultOverlay(game, target, onAction = {
                resetGame(if (game.status == GameStatus.Won) game.level + 1 else game.level)
            })
        } else if (game.isPaused) {
            PauseOverlay(
                onResume = { game = game.copy(isPaused = false) },
                onRestart = { resetGame(game.level) }
            )
        }
    }
}

@Composable
private fun Header(level: Int, isPaused: Boolean, onPauseToggle: () -> Unit, onRestart: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).rotate(-5f).clip(RoundedCornerShape(13.dp)).background(White), contentAlignment = Alignment.Center) {
            Text("✿", color = Rose, fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("西宇消消乐", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text("FRUIT JOURNAL", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        }
        PaperButton(if (isPaused) "继续 ▶" else "暂停 Ⅱ", onPauseToggle)
        Spacer(Modifier.width(6.dp))
        PaperButton("重开 ↻", onRestart)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.rotate(2f).clip(RoundedCornerShape(7.dp)).background(YellowTape).padding(horizontal = 11.dp, vertical = 7.dp)) {
            Text("第 $level 关", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun ScoreAndRounds(game: GameState, target: Int, roundLimit: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        StatCard("得分", game.score.toString(), "✦", Modifier.weight(1.2f))
        StatCard("通关目标", target.toString(), "♡", Modifier.weight(1f))
        StatCard("回合", "${game.round.coerceAtMost(roundLimit)} / $roundLimit", "✿", Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    val progress = (game.score.toFloat() / target).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(9.dp).clip(CircleShape).background(Color(0xFFE8DDD1))) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(BrushGreen))
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(15.dp)).background(White).border(1.dp, Color.White, RoundedCornerShape(15.dp)).padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = Rose, fontSize = 16.sp)
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun MovesCard(moves: Int, roundMoves: Int, round: Int, roundLimit: Int, highlighted: Boolean) {
    val fraction by animateFloatAsState((moves.toFloat() / roundMoves).coerceIn(0f, 1f), label = "moves")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(if (highlighted) Color(0xFFFFE6A5) else Color(0xFFFFFDF8))
        .border(if (highlighted) 2.dp else 0.dp, if (highlighted) Color(0xFFE6A93C) else Color.Transparent, RoundedCornerShape(15.dp))
        .padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("步", color = InkSoft, fontSize = 13.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("回合 $round / $roundLimit", color = InkSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${moves} 步", color = if (moves <= 3) Rose else Ink, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(0xFFE9E1DB))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(if (moves <= 3) Rose else Color(0xFF88AA7B)))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CollageBoard(
    board: List<FruitPiece>,
    size: Int,
    selected: Int?,
    highlighted: Set<Int>,
    enabled: Boolean,
    modifier: Modifier,
    newPieceIds: Set<Long>,
    onSwipeSwap: (Int, Int) -> Unit,
    onTap: (Int) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(size),
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFEDE1D4)).padding(7.dp),
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(board, key = { it.uid }) { piece ->
            val index = board.indexOfFirst { it.uid == piece.uid }
            val fruit = fruitTypes.getOrElse(piece.type) { fruitTypes[0] }
            FruitTile(
                fruit = fruit,
                isSelected = selected == index,
                highlighted = index in highlighted,
                modifier = Modifier.animateItem(
                    placementSpec = spring(dampingRatio = .82f, stiffness = 420f),
                    fadeInSpec = tween(180), fadeOutSpec = tween(130)
                ).aspectRatio(1f),
                enabled = enabled,
                newlySpawned = piece.uid in newPieceIds,
                onSwipe = { deltaX, deltaY ->
                    val neighbor = when {
                        kotlin.math.abs(deltaX) >= kotlin.math.abs(deltaY) -> index + if (deltaX > 0f) 1 else -1
                        else -> index + if (deltaY > 0f) size else -size
                    }
                    if (neighbor in board.indices && areAdjacent(index, neighbor, size)) onSwipeSwap(index, neighbor)
                },
                onClick = { onTap(index) }
            )
        }
    }
}

@Composable
private fun FruitTile(
    fruit: Fruit,
    isSelected: Boolean,
    highlighted: Boolean,
    modifier: Modifier,
    enabled: Boolean,
    newlySpawned: Boolean,
    onSwipe: (Float, Float) -> Unit,
    onClick: () -> Unit
) {
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(if (highlighted) Color(0xFFFFE6A5) else if (isSelected) Color(0xFFFFFDF6) else Color(0xFFF9F5EE))
        .border(if (highlighted) 3.dp else if (isSelected) 2.dp else 1.dp,
            if (highlighted) Color(0xFFE6A93C) else if (isSelected) Rose else Color(0xFFFFFEFA), RoundedCornerShape(9.dp))
        .pointerInput(enabled) {
            if (enabled) {
                var dragX = 0f
                var dragY = 0f
                detectDragGestures(
                    onDragStart = {
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragEnd = {
                        if (dragX != 0f || dragY != 0f) currentOnSwipe(dragX, dragY)
                    },
                    onDrag = { change, amount ->
                        dragX += amount.x
                        dragY += amount.y
                        change.consume()
                    }
                )
            }
        }
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        var visible by remember { mutableStateOf(!newlySpawned) }
        LaunchedEffect(newlySpawned) { if (newlySpawned) visible = true }
        AnimatedVisibility(
            visible = visible,
            enter = if (newlySpawned) slideInVertically(tween(480, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(160)) else EnterTransition.None,
            label = "fruit-drop"
        ) {
            val displayedFruit = fruit
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color = Color.White.copy(alpha = .42f), radius = size.minDimension * .28f, style = Stroke(width = 1.dp.toPx()))
                    drawCircle(color = displayedFruit.color, radius = size.minDimension * .28f)
                    drawCircle(color = Color.White.copy(alpha = .55f), radius = size.minDimension * .055f,
                        center = androidx.compose.ui.geometry.Offset(size.width * .39f, size.height * .38f))
                }
                if (displayedFruit.character != null) {
                    Text(listOf("✿", "✦", "☁", "❋")[displayedFruit.character], color = White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                } else {
                    Text("•", color = White.copy(alpha = .9f), fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun CharacterSkills(game: GameState, enabled: Boolean, onUse: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(White.copy(alpha = .92f)).padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        characterNames.forEachIndexed { i, name ->
            val full = game.energy[i] >= GameConfig.ENERGY_TO_SKILL
            var pulseBright by remember { mutableStateOf(false) }
            LaunchedEffect(full) {
                if (full) {
                    while (true) {
                        pulseBright = !pulseBright
                        delay(550)
                    }
                } else {
                    pulseBright = false
                }
            }
            val glow = if (pulseBright) .82f else .30f
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(fruitTypes[i].color))
                    Spacer(Modifier.width(4.dp))
                    Text(name, fontSize = 10.sp, color = Ink, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(0xFFE8E4E7))) {
                    Box(Modifier.fillMaxWidth((game.energy[i].toFloat() / GameConfig.ENERGY_TO_SKILL).coerceIn(0f,1f)).fillMaxHeight().clip(CircleShape).background(fruitTypes[i].color))
                }
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (full) Color(0xFFFFD985).copy(alpha = glow) else Color.Transparent)
                        .border(if (full) 1.dp else 0.dp, if (full) Color(0xFFE5A437).copy(alpha = glow) else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(enabled = full && enabled && game.status == GameStatus.Playing && !game.isPaused, onClick = { onUse(i) })
                        .heightIn(min = 25.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (full) "✦ ${skills[i]} · 可释放" else "${game.energy[i]}/${GameConfig.ENERGY_TO_SKILL}",
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                        fontSize = 7.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        color = if (full) Color(0xFF89591D) else Muted
                    )
                }
            }
        }
    }
}

@Composable
private fun PaperButton(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(White).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(text, color = InkSoft, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun ResultOverlay(game: GameState, target: Int, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x990F1321)), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(.82f).clip(RoundedCornerShape(25.dp)).background(Paper).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (game.status == GameStatus.Won) "✿" else "☁", fontSize = 45.sp, color = Rose)
            Text(if (game.status == GameStatus.Won) "甜蜜通关！" else "再试一次吧", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(if (game.status == GameStatus.Won) "果园手账又添了新的一页。" else "本局 ${game.score} / $target 分，准备好再挑战了吗？", color = InkSoft, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Ink), shape = RoundedCornerShape(13.dp)) {
                Text(if (game.status == GameStatus.Won) "进入下一关" else "重新开始", color = White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PauseOverlay(onResume: () -> Unit, onRestart: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xAA201D2B)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(.82f).clip(RoundedCornerShape(25.dp)).background(Paper).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Ⅱ", fontSize = 38.sp, color = Rose, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(5.dp))
            Text("休息一下", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(7.dp))
            Text("回合计时已暂停，准备好后继续果园之旅。", color = InkSoft, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onResume, colors = ButtonDefaults.buttonColors(containerColor = Ink), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
                Text("继续游戏", color = White, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onRestart) {
                Text("重新开始本关", color = InkSoft, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun newGame(level: Int): GameState {
    val size = GameConfig.boardSize(level)
    return GameState(level = level, movesLeft = GameConfig.moves(level), board = createBoard(size))
}

private fun createBoard(size: Int): List<FruitPiece> {
    while (true) {
        val board = MutableList(size * size) { newPiece(0) }
        for (i in board.indices) {
            val choices = (0..3).filter { type ->
                !(i % size >= 2 && board[i - 1].type == type && board[i - 2].type == type) &&
                    !(i >= size * 2 && board[i - size].type == type && board[i - size * 2].type == type)
            }
            board[i] = newPiece(choices.random())
        }
        if (hasMove(board, size)) return board
    }
}

private fun areAdjacent(a: Int, b: Int, size: Int) =
    kotlin.math.abs(a % size - b % size) + kotlin.math.abs(a / size - b / size) == 1

private fun findMatches(board: List<FruitPiece>, size: Int): Set<Int> {
    val out = mutableSetOf<Int>()
    for (r in 0 until size) for (c in 0 until size) {
        val i = r * size + c
        val value = board[i].type
        var right = 1
        while (c + right < size && board[i + right].type == value) right++
        if (right >= 3) repeat(right) { out.add(i + it) }
        var down = 1
        while (r + down < size && board[i + down * size].type == value) down++
        if (down >= 3) repeat(down) { out.add(i + it * size) }
    }
    return out
}

private fun hasMove(source: List<FruitPiece>, size: Int): Boolean {
    val board = source.toMutableList()
    for (i in board.indices) for (j in listOf(if (i % size < size - 1) i + 1 else -1, if (i < size * (size - 1)) i + size else -1)) {
        if (j < 0 || board[i].type < 0 || board[j].type < 0) continue
        val t = board[i]; board[i] = board[j]; board[j] = t
        val matched = findMatches(board, size).isNotEmpty()
        val swapped = board[i]; board[i] = board[j]; board[j] = swapped
        if (matched) return true
    }
    return false
}

private fun swapAndResolve(state: GameState, a: Int, b: Int, size: Int): GameState {
    val board = state.board.toMutableList()
    val fallingIds = mutableSetOf<Long>()
    val temp = board[a]; board[a] = board[b]; board[b] = temp
    if (findMatches(board, size).isEmpty()) return state.copy(selected = null, message = "试试能连成三个的交换～")
    var score = state.score
    val energy = state.energy.toMutableList()
    var chain = 0
    while (true) {
        val match = findMatches(board, size)
        if (match.isEmpty()) break
        chain++
        match.forEach { idx ->
            fruitTypes[board[idx].type].character?.let { energy[it] = (energy[it] + 1).coerceAtMost(GameConfig.ENERGY_TO_SKILL) }
            board[idx] = EmptyPiece
        }
        score += match.size * 10 + if (chain > 1) (chain - 1) * 30 else 0
        for (c in 0 until size) {
            var write = size - 1
            for (r in size - 1 downTo 0) {
                val idx = r * size + c
                if (board[idx].type >= 0) { val value = board[idx]; board[idx] = EmptyPiece; board[write * size + c] = value; write-- }
            }
            while (write >= 0) {
                val piece = newPiece(Random.nextInt(4))
                board[write-- * size + c] = piece
                fallingIds += piece.uid
            }
        }
    }
    val playable = hasMove(board, size)
    return state.copy(
        score = score,
        board = board,
        fallingIds = fallingIds,
        energy = energy,
        movesLeft = (state.movesLeft - 1).coerceAtLeast(0),
        selected = null,
        message = when {
            !playable -> "棋盘没有可交换组合，可以发动技能或重开本关。"
            chain > 1 -> "$chain 连消！能量和分数都增加啦 ✦"
            else -> "消除水果，继续加油！"
        }
    )
}

private fun useSkill(state: GameState, id: Int, size: Int): GameState {
    if (state.energy[id] < GameConfig.ENERGY_TO_SKILL) return state
    val board = state.board.toMutableList()
    val energy = state.energy.toMutableList(); energy[id] = 0
    var score = state.score
    var moves = state.movesLeft
    val fallingIds = mutableSetOf<Long>()
    var highlighted = emptySet<Int>()
    var highlightMoves = false
    when (id) {
        0 -> {
            val count = floor(board.size * .20).toInt()
            highlighted = board.indices.shuffled().take(count).toSet()
            highlighted.forEach { board[it] = newPiece(0) }
            resolveBoard(board, size, fallingIds) { score += it }
        }
        1 -> {
            val start = ((size - 3) / 2).coerceAtLeast(0)
            highlighted = (start until (start + 3).coerceAtMost(size)).flatMap { c -> (0 until size).map { r -> r * size + c } }.toSet()
            highlighted.forEach { board[it] = EmptyPiece }
            score += compactColumns(board, size, fallingIds)
            resolveBoard(board, size, fallingIds) { score += it }
        }
        2 -> { moves += 5; highlightMoves = true }
        3 -> {
            val neutral = board.indices.filter { board[it].type >= 4 }
            highlighted = neutral.shuffled().take(floor(board.size * .20).toInt()).toSet()
            highlighted.forEach { board[it] = EmptyPiece }
            score += compactColumns(board, size, fallingIds)
            resolveBoard(board, size, fallingIds) { score += it }
        }
    }
    val playable = hasMove(board, size)
    return state.copy(
        energy = energy,
        score = score,
        movesLeft = moves,
        board = board,
        fallingIds = fallingIds,
        selected = null,
        highlightIndices = highlighted,
        highlightMoves = highlightMoves,
        highlightToken = state.highlightToken + 1,
        message = if (playable) "${characterNames[id]}发动了${skills[id]}！" else "${characterNames[id]}发动了${skills[id]}！棋盘没有可交换组合，请重开本关。"
    )
}

private fun resolveBoard(board: MutableList<FruitPiece>, size: Int, fallingIds: MutableSet<Long>, onScore: (Int) -> Unit) {
    var chain = 0
    while (true) {
        val matches = findMatches(board, size)
        if (matches.isEmpty()) break
        chain++
        matches.forEach { board[it] = EmptyPiece }
        onScore(matches.size * 10 + (chain - 1) * 30)
        compactColumns(board, size, fallingIds)
    }
}

private fun compactColumns(board: MutableList<FruitPiece>, size: Int, fallingIds: MutableSet<Long>): Int {
    var cleared = 0
    for (c in 0 until size) {
        var write = size - 1
        for (r in size - 1 downTo 0) {
            val idx = r * size + c
            if (board[idx].type >= 0) { val value = board[idx]; board[idx] = EmptyPiece; board[write * size + c] = value; write-- }
            else cleared++
        }
        while (write >= 0) {
            val piece = newPiece(Random.nextInt(4))
            board[write-- * size + c] = piece
            fallingIds += piece.uid
        }
    }
    return cleared * 10
}

private fun finishMove(state: GameState, roundLimit: Int, target: Int): GameState {
    if (state.score >= target) return state.copy(status = GameStatus.Won, message = "目标达成！")
    if (state.movesLeft <= 0) return endRound(state, roundLimit, target)
    return state
}

private fun endRound(state: GameState, roundLimit: Int, target: Int): GameState {
    if (state.score >= target) return state.copy(status = GameStatus.Won, message = "目标达成！")
    val neutralCap = floor(state.board.size * GameConfig.NEUTRAL_CAP_RATIO).toInt()
    val nextRound = state.round + 1
    if (nextRound > roundLimit) return state.copy(status = GameStatus.Lost, message = "本关还差一点，再来一次吧。")
    val currentNeutral = state.board.count { it.type >= 4 }
    val stillCanAdd = (neutralCap - currentNeutral).coerceAtLeast(0)
    val add = minOf(4, stillCanAdd)
    val board = state.board.toMutableList()
    board.indices.filter { board[it].type < 4 }.shuffled().take(add).forEach { board[it] = newPiece(4 + Random.nextInt(2)) }
    return state.copy(
        round = nextRound,
        movesLeft = GameConfig.moves(state.level),
        board = board,
        neutralAdded = state.neutralAdded + add,
        selected = null,
        message = if (add > 0) "步数用完：果园添了 $add 个杂色水果！" else "新的一回合开始，继续收集分数！"
    )
}

private val Paper = Color(0xFFFFFFFF)
private val White = Color(0xFFFFFEFA)
private val Ink = Color(0xFF302D3B)
private val InkSoft = Color(0xFF777282)
private val Muted = Color(0xFFA49EAA)
private val Rose = Color(0xFFE3ADB9)
private val YellowTape = Color(0xFFFFE4A7)
private val BrushGreen = Color(0xFF9CB987)
private fun rgb(red: Int, green: Int, blue: Int) = Color(red / 255f, green / 255f, blue / 255f)
