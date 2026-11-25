import { useEffect, useRef, useState } from 'react'
import { createBetta, type BettaColors } from '../lib/betta'
import { FishMark } from './Marks'

const COLORS: Record<string, BettaColors> = {
  light: { deep: [0.16, 0.14, 0.16], pearl: [0.98, 0.97, 0.97], ember: [0.93, 0.31, 0.1] },
  dark: { deep: [0.05, 0.05, 0.08], pearl: [0.86, 0.85, 0.9], ember: [1, 0.4, 0.14] },
}

// Acima disso a placa de vídeo trabalha mais e o olho não percebe, porque a seda é difusa.
const MAX_PIXELS = 1_600_000
const STILL_TIME = 4.2

/** Decorativo: para quem usa leitor de tela, não existe. */
export function BettaCanvas() {
  const ref = useRef<HTMLCanvasElement>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const canvas = ref.current
    if (!canvas) return
    let betta: ReturnType<typeof createBetta> = null
    try {
      betta = createBetta(canvas)
    } catch (error) {
      console.error(error)
    }
    if (!betta) {
      setFailed(true)
      return
    }
    const fish = betta
    const motion = matchMedia('(prefers-reduced-motion: reduce)')
    const target: [number, number] = [0, 0]
    const pointer: [number, number] = [0, 0]
    let frame = 0
    let visible = true
    let time = STILL_TIME
    let last = 0

    const paint = () => {
      const colors = COLORS[document.documentElement.dataset.theme ?? 'light'] ?? COLORS.light!
      fish.draw({ time, width: canvas.width, height: canvas.height, pointer, colors })
    }
    const loop = (now: number) => {
      // Voltar de uma aba escondida não pode fazer o peixe saltar.
      time += Math.min((now - last) / 1000, 0.05)
      last = now
      pointer[0] += (target[0] - pointer[0]) * 0.04
      pointer[1] += (target[1] - pointer[1]) * 0.04
      paint()
      frame = requestAnimationFrame(loop)
    }
    const update = () => {
      cancelAnimationFrame(frame)
      if (visible && !document.hidden && !motion.matches) {
        last = performance.now()
        frame = requestAnimationFrame(loop)
      } else if (visible) {
        paint()
      }
    }
    const resize = () => {
      const { clientWidth, clientHeight } = canvas
      if (clientWidth === 0 || clientHeight === 0) return
      const ratio = Math.min(devicePixelRatio, 2, Math.sqrt(MAX_PIXELS / (clientWidth * clientHeight)))
      canvas.width = Math.round(clientWidth * ratio)
      canvas.height = Math.round(clientHeight * ratio)
      paint()
    }
    const follow = (event: PointerEvent) => {
      if (event.pointerType === 'touch') return
      target[0] = (event.clientX / innerWidth) * 2 - 1
      target[1] = (event.clientY / innerHeight) * 2 - 1
    }
    const lost = (event: Event) => {
      event.preventDefault()
      cancelAnimationFrame(frame)
      setFailed(true)
    }

    const sizes = new ResizeObserver(resize)
    const sight = new IntersectionObserver(([entry]) => {
      visible = entry!.isIntersecting
      update()
    })
    const theme = new MutationObserver(paint)
    sizes.observe(canvas)
    sight.observe(canvas)
    theme.observe(document.documentElement, { attributeFilter: ['data-theme'] })
    addEventListener('pointermove', follow, { passive: true })
    document.addEventListener('visibilitychange', update)
    motion.addEventListener('change', update)
    canvas.addEventListener('webglcontextlost', lost)
    resize()
    update()

    return () => {
      cancelAnimationFrame(frame)
      sizes.disconnect()
      sight.disconnect()
      theme.disconnect()
      removeEventListener('pointermove', follow)
      document.removeEventListener('visibilitychange', update)
      motion.removeEventListener('change', update)
      canvas.removeEventListener('webglcontextlost', lost)
      // Sem destruir o contexto: no modo estrito o React remonta no mesmo canvas.
    }
  }, [])

  return (
    <div className="betta" aria-hidden="true" data-failed={failed}>
      {failed ? <FishMark /> : <canvas ref={ref} />}
    </div>
  )
}
