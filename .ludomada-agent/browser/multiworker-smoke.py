import asyncio
from termux_playwright import async_playwright_termux

CDP = "http://127.0.0.1:9222"
URLS = [
    "https://example.com/?worker=mvola",
    "https://example.org/?worker=airtel",
    "https://example.net/?worker=orange",
    "https://example.com/?worker=binance",
    "https://example.org/?worker=cloud",
    "https://example.net/?worker=qa",
]

async def main():
    async with async_playwright_termux() as p:
        browser = await p.chromium.connect_over_cdp(CDP)
        context = browser.contexts[0]
        pages = await asyncio.gather(*(context.new_page() for _ in URLS))
        await asyncio.gather(*(page.goto(url, timeout=30000) for page, url in zip(pages, URLS)))
        titles = await asyncio.gather(*(page.title() for page in pages))
        print("CDP_MULTIWORKER=PASS")
        print(f"WORKERS={len(pages)}")
        for i, title in enumerate(titles, 1):
            print(f"WORKER_{i}={title}")
        await asyncio.gather(*(page.close() for page in pages))

if __name__ == "__main__":
    asyncio.run(main())
