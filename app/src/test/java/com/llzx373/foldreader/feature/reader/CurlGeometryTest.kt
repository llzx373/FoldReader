package com.llzx373.foldreader.feature.reader

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * 卷曲几何的不变量。六条硬保证：
 * 1. **正在读的正文不动**——折痕未翻起一侧的网格顶点逐点等于原位置；
 * 2. **θ ≤ 90° 时翻起的纸全部可见**——投影沿弧长严格单调、没有"折回去被外层遮住"的区段
 *    （这是把"整段裹在圆柱上"换成"紧圆角 + 斜面"的原因：旧模型会吃掉一大段正文）；
 * 3. **两端严格收敛**——p=0 全屏当前页、p=1 整块纸滑出叶片（全屏下层页）；
 * 4. **折痕不越过中缝**——卷曲带多边形恒在叶片矩形内；
 * 5. **折痕单调推进**；
 * 6. **起手/接管不跳帧**——锚定量与虚拟起手点。
 */
class CurlGeometryTest {

    private val frame = CurlFrame(originX = 0f, side = 1f, width = 1000f, height = 800f)
    private val rightDir = Offset(1f, 0f)
    private val diagDir = Offset(0.747f, 0.664f)

    private fun roll(p: Float, dir: Offset = rightDir, on: CurlFrame = frame) =
        curlRollForProgress(on, dir, p)

    private fun alongT(roll: CurlRoll, t: Float) =
        Offset(roll.crease.x + roll.dir.x * t, roll.crease.y + roll.dir.y * t)

    // ---------- 两端收敛 ----------

    @Test
    fun `start state is the untouched current page`() {
        val r = roll(0f)
        assertTrue("p=0 不应有翻起", r.degenerate)
        for (corner in frame.corners) {
            assertEquals(CurlSurface.FLAT, curlSurfaceAt(r, corner))
        }
        val (mw, mh) = curlMeshSize(frame, 40f)
        val verts = curlMeshVertices(r, frame, mw, mh)
        for (row in 0..mh) {
            for (col in 0..mw) {
                val i = (row * (mw + 1) + col) * 2
                assertEquals(frame.width * col / mw, verts[i], 0.001f)
                assertEquals(frame.height * row / mh, verts[i + 1], 0.001f)
            }
        }
    }

    @Test
    fun `end state leaves the whole leaf beyond the curled part`() {
        for (dir in listOf(rightDir, diagDir, Offset(-1f, 0f))) {
            val r = roll(1f, dir)
            assertTrue("末态应有翻起", !r.degenerate)
            for (corner in frame.corners) {
                val t = curlOffset(r, corner)
                assertTrue(
                    "p=1 时叶片应整体落在翻起部分之外：t=$t rollEdge=${r.rollEdge}",
                    t > r.rollEdge,
                )
                assertEquals(CurlSurface.UNDER, curlSurfaceAt(r, corner))
            }
        }
    }

    @Test
    fun `crease sweeps monotonically and never jumps back`() {
        var prev = -Float.MAX_VALUE
        for (i in 0..40) {
            val r = roll(i / 40f)
            val u = curlOffset(r, frame.center)
            assertTrue("折痕应单调推进：$prev -> $u", u >= prev - 0.001f)
            prev = u
        }
        assertTrue("折痕应扫过整页", prev > 600f)
    }

    @Test
    fun `rolled length never exceeds the leaf extent`() {
        for (i in 0..20) {
            val r = roll(i / 20f)
            assertTrue("可翻起长度不得超过叶片跨度", r.length <= 1000f + 0.5f)
            assertTrue(r.length >= 0f)
            assertTrue("抬角应在 0..π", r.angle in 0f..PI.toFloat() + 0.001f)
        }
    }

    // ---------- 关键：翻起的纸全部可见（旧模型的病根） ----------

    @Test
    fun `every bit of the lifted paper stays visible before the page goes vertical`() {
        // θ = 90°（p=0.5）时整页正好侧立，所有弧长投影到同一条线上，是几何本身的退化点
        for (p in listOf(0.1f, 0.25f, 0.4f, 0.48f)) {
            for (dir in listOf(rightDir, diagDir)) {
                val r = roll(p, dir)
                assertTrue("θ ≤ 90° 时不应出现纸背", !r.hasBack)

                var prevT = -Float.MAX_VALUE
                var s = 0f
                val step = r.length / 60f
                while (s <= r.length + 0.001f) {
                    val t = curlProject(r, s)
                    assertTrue(
                        "投影应沿弧长单调（否则网格会折回去遮住文字）：s=$s t=$t (p=$p)",
                        t >= prevT - 0.001f,
                    )
                    prevT = t
                    s += step
                }
                assertEquals("自由边应落在外缘", r.planeEnd, curlProject(r, r.length), 1f)
                assertEquals("正面可见范围应覆盖整段翻起", r.planeEnd, r.frontMax, 0.001f)

                // 翻起段上每个位置都判成正面（不是被遮住、也不是下层页）。
                // 取边界内侧一点测量：屏幕坐标往返有浮点抵消，正好压在自由边上会误判。
                var arc = 0f
                while (arc <= r.length) {
                    val t = curlProject(r, arc)
                    val probe = alongT(r, (t - 0.5f).coerceAtLeast(0f))
                    assertEquals(
                        "翻起的纸应可见 (p=$p arc=$arc t=$t)",
                        CurlSurface.FRONT,
                        curlSurfaceAt(r, probe),
                    )
                    arc += r.length / 12f
                }
            }
        }
    }

    @Test
    fun `back side only appears after the page passes vertical`() {
        val before = roll(0.45f)
        assertTrue("90° 之前没有纸背", !before.hasBack)
        val after = roll(0.8f)
        assertTrue("过 90° 之后出现纸背", after.hasBack)
        assertTrue("纸背应落在折痕左侧（压在平铺页上）", after.backMin < 0f)
        assertEquals(
            CurlSurface.BACK,
            curlSurfaceAt(after, alongT(after, (after.backMin + after.backMax) / 2f)),
        )
    }

    // ---------- 变形 ----------

    @Test
    fun `flat side is not moved at all`() {
        val r = roll(0.3f)
        for (x in listOf(0f, 100f, 300f)) {
            if (x >= r.crease.x) continue
            val p = Offset(x, 200f)
            assertEquals(p, curlWarpPoint(r, p))
        }
    }

    @Test
    fun `warped points stay bounded under perspective`() {
        for (p in listOf(0.2f, 0.4f, 0.6f, 0.9f)) {
            val r = roll(p)
            // 透视放大率有限：翻起的纸可略微超出抬起前的位置，但不允许发散
            val mMax = curlScale(r, 0f).let { _ ->
                var m = 1f
                var s = 0f
                while (s <= r.length) {
                    m = maxOf(m, curlScale(r, s))
                    s += r.length / 20f
                }
                m
            }
            assertTrue("放大率应有界：$mMax (p=$p)", mMax in 1f..2.5f)
            var x = 0f
            while (x <= frame.width) {
                val warped = curlWarpPoint(r, Offset(x, 400f))
                assertTrue(
                    "翻起后的点不得发散：$warped (p=$p)",
                    warped.x > -frame.width * 3f && warped.x < frame.width * 3f,
                )
                x += 7f
            }
        }
    }

    @Test
    fun `crease line stays within the page for the meaningful range`() {
        // 折痕是"折"出来的那条线，它在页面内推进；扫过装订边之后整块纸就该滑走了
        for (p in listOf(0.2f, 0.4f, 0.6f, 0.7f)) {
            val r = roll(p)
            val creaseX = r.crease.x
            assertTrue("折痕应在页面内：$creaseX (p=$p)", creaseX >= -1f && creaseX <= frame.width + 1f)
        }
    }

    @Test
    fun `band overhang under perspective stays bounded`() {
        val rect = frame.screenRect
        val slack = frame.width * 0.9f
        for (p in listOf(0.2f, 0.4f, 0.6f, 0.9f)) {
            val r = roll(p)
            val poly = curlBandPolygon(r, frame, r.flatEdge, r.rollEdge)
            if (poly.isEmpty()) continue
            for (q in poly) {
                assertTrue("x 越界过多: $q (p=$p)", q.x >= rect.left - slack && q.x <= rect.right + slack)
                assertTrue("y 越界过多: $q (p=$p)", q.y >= rect.top - slack && q.y <= rect.bottom + slack)
            }
        }
    }

    @Test
    fun `mesh keeps the uncurled vertices pixel exact`() {
        val r = roll(0.25f)
        val (mw, mh) = curlMeshSize(frame, 20f)
        val verts = curlMeshVertices(r, frame, mw, mh)
        var checked = 0
        for (row in 0..mh) {
            for (col in 0..mw) {
                val localX = frame.width * col / mw
                val localY = frame.height * row / mh
                if (localX >= r.crease.x) continue
                val i = (row * (mw + 1) + col) * 2
                assertEquals("x 不应变", localX, verts[i], 0.001f)
                assertEquals("y 不应变", localY, verts[i + 1], 0.001f)
                checked++
            }
        }
        assertTrue("应有足够多的平板顶点被检查", checked > 20)
    }

    @Test
    fun `mesh vertex count matches the grid and stays under the cap`() {
        val (mw, mh) = curlMeshSize(frame, 20f)
        assertEquals((mw + 1) * (mh + 1) * 2, curlMeshVertices(roll(0.5f), frame, mw, mh).size)

        val huge = CurlFrame(0f, 1f, 6000f, 4000f)
        val (hw, hh) = curlMeshSize(huge, 5f)
        assertTrue((hw + 1) * (hh + 1) <= MAX_CURL_MESH_VERTICES)
    }

    @Test
    fun `bend radius is a small fraction of the leaf`() {
        // 圆角要"紧"：这是与旧模型（整段裹圆柱）最本质的区别
        val r = roll(0.5f)
        assertTrue("圆角半径应远小于页宽", r.bendRadius < frame.width * 0.1f)
        assertTrue("圆角半径应大于 0", r.bendRadius > 0f)
    }

    // ---------- 拖拽跟手 ----------

    @Test
    fun `progress inverts the roll construction`() {
        for (dir in listOf(rightDir, diagDir, Offset(-1f, 0f))) {
            for (p in listOf(0.1f, 0.35f, 0.6f, 0.9f)) {
                val r = roll(p, dir)
                assertEquals("进度应可逆 (dir=$dir p=$p)", p, curlProgressFor(frame, dir, r.crease), 0.002f)
            }
        }
    }

    @Test
    fun `grab anywhere starts from zero progress`() {
        for (dir in listOf(rightDir, diagDir, Offset(-1f, 0f))) {
            for (grab in listOf(Offset(500f, 400f), Offset(300f, 120f), Offset(800f, 700f))) {
                assertEquals(
                    "起手进度应为 0 (dir=$dir grab=$grab)",
                    0f,
                    curlDragProgress(frame, dir, grab, grab),
                    0.001f,
                )
            }
        }
    }

    @Test
    fun `drag progress grows as the finger travels along the fold direction`() {
        val grab = Offset(800f, 300f)
        var prev = -1f
        var x = 800f
        while (x >= 0f) {
            val p = curlDragProgress(frame, rightDir, grab, Offset(x, 300f))
            assertTrue("拖拽进度应单调不减：$prev -> $p (x=$x)", p >= prev - 0.001f)
            assertTrue(p in 0f..1f)
            prev = p
            x -= 40f
        }
        assertTrue("拖到底应接近完成", prev > 0.6f)
    }

    @Test
    fun `drag progress leaves the fold exactly under the finger after catch-up`() {
        val finger = Offset(300f, 300f)
        val p = curlDragProgress(frame, rightDir, Offset(700f, 300f), finger)
        assertEquals("折痕应贴在触点下", finger.x, roll(p).crease.x, 0.5f)
    }

    @Test
    fun `resuming a flying turn does not jump`() {
        val dir = rightDir
        for (p in listOf(0.2f, 0.5f, 0.8f)) {
            // 接管点取当前进度对应的折痕位置——那一帧进度必须原样保持
            val takeOverAt = Offset(roll(p).crease.x, 400f)
            assertEquals(
                "接管那一帧进度不应跳 (p=$p)",
                p,
                curlResumeProgress(frame, dir, takeOverAt, takeOverAt, p),
                0.001f,
            )
            // 继续往翻页方向拖，进度应随之增大
            val further = Offset(takeOverAt.x - 60f, 400f)
            assertTrue(
                "接管后继续拖应推进",
                curlResumeProgress(frame, dir, further, takeOverAt, p) > p,
            )
        }
    }

    @Test
    fun `direction needs a real drag before it is defined`() {
        assertEquals(null, curlDirectionFor(Offset(10f, 10f), Offset(10f, 10f)))
        assertEquals(null, curlDirectionFor(Offset(10f, 10f), Offset(12f, 10f)))
        val d = curlDirectionFor(Offset(100f, 100f), Offset(40f, 100f))
        assertNotNull(d)
        assertEquals(1f, d!!.x, 0.001f)
    }

    // ---------- 折痕不越过中缝 ----------

    @Test
    fun `crease never crosses the spine while the peel is on the page`() {
        // 双页时叶片坐标系边界就是中缝。剥纸阶段（折痕还在页面里）折痕不得越到对面那页。
        // 折痕扫过装订边之后整块纸就该滑走了，那之后不在本约束内。
        val right = CurlFrame(originX = 780f, side = 1f, width = 780f, height = 1400f)
        for (dir in listOf(Offset(1f, 0f), Offset(0.8f, -0.6f), Offset(0.8f, 0.6f))) {
            for (p in listOf(0.1f, 0.2f, 0.3f, 0.4f)) {
                val r = curlRollForProgress(right, dir, p)
                assertTrue(
                    "右页折痕越过中缝：${r.crease.x} (p=$p dir=$dir)",
                    r.crease.x >= 780f - 1f,
                )
            }
        }
        val left = CurlFrame(originX = 780f, side = -1f, width = 780f, height = 1400f)
        assertEquals(0f, left.screenRect.left, 0.001f)
        assertEquals(780f, left.screenRect.right, 0.001f)
        for (dir in listOf(Offset(-1f, 0f), Offset(-0.8f, 0.6f))) {
            for (p in listOf(0.1f, 0.2f, 0.3f, 0.4f)) {
                val r = curlRollForProgress(left, dir, p)
                assertTrue(
                    "左页折痕越过中缝：${r.crease.x} (p=$p dir=$dir)",
                    r.crease.x <= 780f + 1f,
                )
            }
        }
    }

    // ---------- 覆盖率 ----------

    @Test
    fun `lifted area grows early then foreshortens away`() {
        // 前段：纸越掀越多，屏幕上的翻起面积单调增大
        var prev = -1f
        var p = 0f
        while (p <= 0.2001f) {
            val r = roll(p)
            val covered = polygonArea(curlBandPolygon(r, frame, r.flatEdge, r.rollEdge))
            assertTrue("翻起面积前段应单调不减：$prev -> $covered (p=$p)", covered >= prev - 0.5f)
            prev = covered
            p += 0.05f
        }
        assertTrue("翻起面积应成形", prev > 10_000f)

        // 后段：纸转向侧立、透视缩短，面积回落；末态整块滑出
        val peakRoll = roll(0.25f)
        val peaked = polygonArea(
            curlBandPolygon(peakRoll, frame, peakRoll.flatEdge, peakRoll.rollEdge),
        )
        val midRoll = roll(0.45f)
        val mid = polygonArea(
            curlBandPolygon(midRoll, frame, midRoll.flatEdge, midRoll.rollEdge),
        )
        assertTrue("过 45° 之后应因透视缩短而变小", mid < peaked)
        val end = roll(1f)
        assertEquals(0f, polygonArea(curlBandPolygon(end, frame, end.flatEdge, end.rollEdge)), 1f)
    }

    @Test
    fun `front clip covers every flat point on screen`() {
        // 回归锁：平铺页在 flatEdge 之外（t < flatEdge）。正面的裁剪下界若取 flatEdge，
        // 平铺页会整片漏画、下层页透出来——真机表现就是"翻页时左边已经变成后一页的内容"。
        for (p in listOf(0.15f, 0.35f, 0.6f, 0.85f)) {
            val r = roll(p)
            val front = curlBandPolygon(r, frame, CURL_OPEN_LOWER_BOUND, r.frontMax)
            var flatSeen = 0
            var x = frame.screenRect.left + 1f
            while (x <= frame.screenRect.right - 1f) {
                val probe = Offset(x, frame.screenRect.center.y)
                if (curlSurfaceAt(r, probe) == CurlSurface.FLAT) {
                    flatSeen++
                    assertTrue("有平铺页就必须有正面裁剪 (p=$p x=$x)", front.size >= 3)
                    assertTrue(
                        "平铺页必须被正面裁剪覆盖 (p=$p x=$x)",
                        insideConvex(front, probe),
                    )
                    // 平铺部分的网格顶点是恒等的，就靠这次绘制画出来
                    assertEquals(probe, curlWarpPoint(r, probe))
                }
                x += 10f
            }
            // 起手阶段屏幕上一定有平铺页
            if (p <= 0.6f) assertTrue("前中段应能看到平铺页 (p=$p)", flatSeen > 1)
        }
    }

    /** 凸多边形内判定（射线法）。 */
    private fun insideConvex(poly: List<Offset>, q: Offset): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val cross = (b.x - a.x) * (q.y - a.y) - (b.y - a.y) * (q.x - a.x)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    @Test
    fun `frame maps local coordinates both ways`() {
        val right = CurlFrame(780f, 1f, 780f, 1400f)
        assertEquals(Offset(980f, 300f), right.toScreen(Offset(200f, 300f)))
        assertEquals(Offset(200f, 300f), right.toLocal(Offset(980f, 300f)))

        val left = CurlFrame(780f, -1f, 780f, 1400f)
        assertEquals(Offset(580f, 300f), left.toScreen(Offset(200f, 300f)))
        assertEquals(Offset(200f, 300f), left.toLocal(Offset(580f, 300f)))
    }

    @Test
    fun `roll bounds stay inside the leaf rect`() {
        val b = curlRollBounds(roll(0.5f), frame)
        assertTrue(b.left >= frame.screenRect.left - 0.01f)
        assertTrue(b.right <= frame.screenRect.right + 0.01f)
    }
}
