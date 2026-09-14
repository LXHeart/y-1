// @vitest-environment happy-dom
import { afterEach, describe, expect, test } from 'vitest'
import { handleTabKeydown } from './tab-navigation'

afterEach(() => document.body.replaceChildren())

function tabs() {
  document.body.innerHTML = '<nav role="tablist"><button role="tab" aria-selected="true">任务</button>'
    + '<button role="tab" tabindex="-1" aria-selected="false">主体</button>'
    + '<button role="tab" disabled>不可用</button><button role="tab" hidden>隐藏</button>'
    + '<button role="tab" tabindex="-1" aria-selected="false">资金</button></nav>'
  const list = document.querySelector<HTMLElement>('[role="tablist"]')!
  list.addEventListener('keydown', handleTabKeydown)
  list.querySelectorAll<HTMLButtonElement>('button').forEach(button => button.addEventListener('click', () => {
    list.querySelectorAll('button').forEach(item => item.setAttribute('aria-selected', String(item === button)))
  }))
  return [...list.querySelectorAll<HTMLButtonElement>('button')]
}

function key(button: HTMLElement, value: string, extra: KeyboardEventInit = {}) {
  const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...extra })
  button.dispatchEvent(event)
  return event
}

describe('tab navigation', () => {
  test('arrow keys reach inactive tabs, select them and skip disabled/hidden choices', () => {
    const [first, second, , , last] = tabs()
    first.focus()
    expect(key(first, 'ArrowRight').defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(second)
    expect(second.getAttribute('aria-selected')).toBe('true')
    key(second, 'ArrowDown')
    expect(document.activeElement).toBe(last)
    key(last, 'ArrowRight')
    expect(document.activeElement).toBe(first)
    key(first, 'ArrowLeft')
    expect(document.activeElement).toBe(last)
  })

  test('Home and End focus the endpoints, leaving modified keys and Tab to the browser', () => {
    const [first, second, , , last] = tabs()
    second.focus(); key(second, 'End'); expect(document.activeElement).toBe(last)
    key(last, 'Home'); expect(document.activeElement).toBe(first)
    expect(key(first, 'ArrowRight', { ctrlKey: true }).defaultPrevented).toBe(false)
    expect(key(first, 'Tab').defaultPrevented).toBe(false)
    expect(document.activeElement).toBe(first)
  })

  test('text inputs inside a tablist retain their own cursor keys', () => {
    const [first] = tabs(), input = document.createElement('input')
    first.parentElement!.append(input); input.focus()
    expect(key(input, 'ArrowLeft').defaultPrevented).toBe(false)
    expect(document.activeElement).toBe(input)
  })
})
