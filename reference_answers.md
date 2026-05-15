# Reference Claude-quality answers (8 questions)

These are the answers I'd give as Claude. Used for grading Localyze responses.

---

## Q1_TCP_UDP — TCP vs UDP, 4 bullets

- **TCP**: connection-oriented, reliable, ordered. Use it when every byte must arrive (HTTP/HTTPS, SSH, file transfer, databases).
- **UDP**: connectionless, no delivery guarantee, low overhead. Use it when latency matters more than completeness (live video, voice, gaming, DNS).
- **Cost**: TCP adds handshake + retransmission + congestion control; UDP just fires packets. UDP is faster but you build any reliability you need on top.
- **Rule of thumb**: pick TCP unless you have a measured latency or throughput reason to drop it.

---

## Q2_TRAIN_MATH — When do they meet?

Closing speed = 60 + 75 = 135 mph.

Train A leaves at 9:00 AM. By 9:30 AM (when train B leaves), train A has covered 60 × 0.5 = 30 miles, leaving 270 miles between them.

Time to close 270 miles at 135 mph = 270 / 135 = **2 hours**.

So they meet at 9:30 AM + 2:00 = **11:30 AM**.

---

## Q3_CODE_RUN — longest_run

```python
def longest_run(nums):
    if not nums:
        return 0
    best = current = 1
    for prev, cur in zip(nums, nums[1:]):
        current = current + 1 if cur == prev else 1
        if current > best:
            best = current
    return best

# Example
print(longest_run([1, 2, 2, 3, 3, 3, 1]))  # -> 3
```

---

## Q4_APOLOGY — 3-sentence apology email

> Subject: We're sorry for the delay on your order
>
> I'm sorry your order arrived five days later than promised — a fire at our warehouse forced an unplanned shutdown, and yours was caught in the backlog. We're fast-tracking your shipment now and will email tracking the moment it leaves our facility. As an apology, we've added a 20% credit to your account; please reply to this message if there's anything else we can do.

---

## Q5_HASH_TABLE — Hash table for a beginner, with analogy

A hash table is a way to store labelled items so you can find any one of them almost instantly, no matter how many you have.

Think of a wall of numbered mailboxes in an apartment building. To deliver to "Alice in 3B", the mailman doesn't read every nameplate — he uses the address to walk straight to box 3B. A hash table does the same trick: it converts your label (the *key*, e.g. "Alice") into a box number using a small math function (the *hash*), and stores the value in that box. To look it up, it runs the same math and goes straight to the box.

That's why it's fast: finding an item doesn't depend on how many items are stored — it's roughly the same effort whether the table has 10 entries or 10 million.

(Caveat: two keys can hash to the same box — a *collision* — and the table handles that by keeping a small list per box. Done well, that list stays tiny on average.)

---

## Q6_PYTHON_VERSION — Latest stable Python

(Online expectation): Cite the latest CPython release with date and a short note on the previous LTS-style line. As of early 2026, the answer is roughly: Python 3.13.x is the current stable line (released 2024-10-07 for 3.13.0; later patch versions follow). Python 3.14 is in development. Cite python.org/downloads or python.org release notes.

(Offline expectation): "I'm not online, so I can't verify the very latest patch — as of my knowledge, Python 3.13 is the most recent stable major release (3.13.0 was October 2024). 3.14 was in development. Check python.org for the current point release."

---

## Q7_BTC_PRICE — Current Bitcoin price

(Online): Cite the current spot price in USD with a timestamp and source (e.g. CoinGecko, CoinMarketCap, Coinbase). Note that crypto prices move continuously.

(Offline): "I don't have a live price feed. Bitcoin's price moves continuously, so any number I give you would be stale — check CoinGecko, CoinMarketCap, or your exchange for the current spot price."

---

## Q8_APPLES — State tracking

Start: I have 3, you have 0.

1. I give you 2 → I have 1, you have 2.
2. I eat 1 of mine → I have 0, you have 2.
3. You give me back 1 → I have 1, you have 1.

**End: I have 1 apple, you have 1 apple.**
